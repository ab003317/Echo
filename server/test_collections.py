import json
import time
from unittest.mock import Mock

import pytest
from server.test_app import api
from server.test_radio import send, pool
from server.traits import validate_record


def seed_songs(a, count=36):
    sample=a.library()[0]
    with a.db() as c:
        for i in range(count):
            path=f'sample-{i}.wav'
            (a.ROOT/path).write_bytes(b'audio')
            c.execute('INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                (f'track-{i}',path,f'Song {i}',f'Artist {i%4}',f'Album {i%3}',180,5,123,123,'metal',0))
    return [s for s in a.library() if s['id']!=sample['id']]


def feature(song, genre='rock', confidence=.9):
    return {'track':song['id'],'mtime':song['mtime'],'size':song['size'],'music':True,
            'segments':[[20,20],[80,20],[140,20]],'sampledSeconds':60,'inputAudioSha256':'a'*64,
            'traits':[],'genres':[{'tag':genre,'confidence':confidence,'atSecond':10}]}


def test_history_short_sessions_repeat_legacy_and_pagination(api):
    c,a=api; tid=a.library()[0]['id']
    send(c,tid,'session-short',seconds=2,outcome='skipped',at=1000)
    send(c,tid,'session-long-a',seconds=25,at=1000)
    send(c,tid,'session-long-a',seconds=40,seq=2,outcome='completed',at=1040)
    send(c,tid,'session-long-a',seconds=1,seq=1,at=1000)
    c.post('/music/api/listens',json={'event':'legacy-listen','track':tid,'seconds':30,'at':800})
    first=c.get('/music/api/history?limit=1').json()
    assert first['entries'][0]['event']=='session-short' and first['hasMore']
    second=c.get('/music/api/history',params={'limit':1,'cursor':first['nextCursor']}).json()
    assert second['entries'][0]['seconds']==40 and second['entries'][0]['outcome']=='completed'
    third=c.get('/music/api/history',params={'cursor':second['nextCursor']}).json()
    assert [r['event'] for r in third['entries']]==['legacy-listen'] and not third['hasMore']
    assert len(c.get('/music/api/history?q=Song').json()['entries'])==3
    assert not c.get('/music/api/history?q=unknown').json()['entries']
    assert c.get('/music/api/history?cursor=invalid').status_code==400
    assert c.get('/music/api/history?limit=0').status_code==422


def test_history_remembers_metadata_after_audio_removed(api):
    c,a=api;tid=a.library()[0]['id']
    send(c,tid,seconds=3,outcome='stopped')
    (a.ROOT/'Artist_Song.wav').unlink();a.scan()
    entry=c.get('/music/api/history').json()['entries'][0]
    assert entry['song']['title']=='Song' and entry['song']['artist']=='Artist'
    assert not entry['available'] and 'path' not in entry['song']


def test_mix_snapshot_stability_rotation_and_save_pins(api):
    c,a=api;seed_songs(a)
    tid=pool(a)
    initial=c.get('/music/api/mixes').json()
    assert c.get('/music/api/mixes').json()==initial
    assert {m['kind'] for m in initial['mixes']}=={'artist','series','random'}
    chosen=next(m for m in initial['mixes'] if any(s['id']==tid for s in m['tracks']))
    members=[s['id'] for s in chosen['tracks']]
    stamp=time.time()
    assert c.put(f"/music/api/mixes/{chosen['id']}/saved",json={'saved':True,'updated':stamp}).status_code==200
    with a.db() as con:
        assert a.mixes.protected(con,tid)
        assert con.execute('SELECT COUNT(*) FROM listens').fetchone()[0]==0
        assert con.execute('SELECT COUNT(*) FROM favorites').fetchone()[0]==0
        assert con.execute('SELECT state FROM pool_entries WHERE track=?',(tid,)).fetchone()[0]=='kept'
    a.mixes.ensure(initial['nextRefresh']+1)
    rotated=c.get('/music/api/mixes').json()
    saved=next(m for m in rotated['mixes'] if m['id']==chosen['id'])
    assert saved['saved'] and not saved['active'] and [s['id'] for s in saved['tracks']]==members
    assert {m['id'] for m in rotated['mixes'] if m['active']}.isdisjoint(m['id'] for m in initial['mixes'])
    # Late stale offline writes cannot undo a newer save.
    c.put(f"/music/api/mixes/{chosen['id']}/saved",json={'saved':False,'updated':stamp-1})
    assert next(m for m in a.mixes.feed()['mixes'] if m['id']==chosen['id'])['saved']
    a.mixes.save(chosen['id'],False,time.time())
    with a.db() as con:assert not a.mixes.protected(con,tid)
    # An offline phone can still save its edition after rotation.
    a.mixes.save(chosen['id'],True,time.time())
    assert next(m for m in a.mixes.feed()['mixes'] if m['id']==chosen['id'])['saved']


def test_saved_pins_survive_cleanup_and_unsave_preserves_listens(api):
    c,a=api;tid=pool(a,'retiring')
    # A pending retirement is eligible for rescue through its previously generated edition.
    with a.db() as con:con.execute("UPDATE pool_entries SET state='explore'")
    mid=a.mixes.feed()['mixes'][0]['id']
    with a.db() as con:con.execute("UPDATE pool_entries SET state='retiring',retire_at=1")
    a.mixes.save(mid,True,time.time())
    a.radio.rotate('2026-11-01',time.time()+86400*5)
    assert a.library()[0]['pool']=='kept'
    send(c,tid,seconds=1,outcome='skipped')
    a.mixes.save(mid,False,time.time())
    assert a.library()[0]['pool']=='kept'


def test_saved_owned_audio_cannot_be_cleaned_and_multiple_saved_editions_hold_pin(api):
    c,a=api;tid=pool(a)
    owned=a.ROOT/'Serein Discoveries';owned.mkdir()
    path=owned/'abcdefghijk.m4a'
    (a.ROOT/'Artist_Song.wav').rename(path)
    with a.db() as con:con.execute('UPDATE tracks SET path=? WHERE id=?',('Serein Discoveries/abcdefghijk.m4a',tid))
    first=a.mixes.feed()['mixes'][0]['id'];a.mixes.save(first,True,time.time())
    a.mixes.ensure(a.mixes.get('nextRefresh')+1)
    second=next(m for m in a.mixes.feed()['mixes'] if m['active'])['id'];a.mixes.save(second,True,time.time())
    a.mixes.save(first,False,time.time())
    with a.db() as con:con.execute("UPDATE pool_entries SET state='retiring',retire_at=1,last_access=0")
    a.radio.cleanup()
    assert path.exists() and a.library()[0]['pool']=='kept'
    a.mixes.save(second,False,time.time())
    with a.db() as con:con.execute("UPDATE pool_entries SET state='retiring',retire_at=1,last_access=0")
    a.radio.cleanup()
    assert not path.exists()


def test_audio_maps_only_real_ids(api,monkeypatch):
    _,a=api;song=a.library()[0]
    monkeypatch.setattr(a.traits,'prepare',lambda _: {**feature(song),'creditedArtist':song['artist'],'audio':b'audio'})
    result={'audioRead':True,'tracks':[{'id':'S00','music':True,'traits':[],'genres':[{'tag':'rock','confidence':.9,'atSecond':9}]}]}
    monkeypatch.setattr('server.traits.AIClient.generate',lambda *args: (result,'audio-model'))
    records=a.traits.analyze([song])
    assert records[0]['track']==song['id'] and records[0]['model']=='audio-model'
    assert a.mixes.features()[song['id']]['genres'][0]['tag']=='rock'
    result['tracks'][0]['id']='invented-id'
    with pytest.raises(RuntimeError):a.traits.analyze([song])


def test_styles_require_current_audio_evidence_and_config_does_not_shuffle(api):
    c,a=api;songs=seed_songs(a)
    assert not any(m['kind']=='style' for m in a.mixes.feed()['mixes'])  # File genre/title is not evidence.
    records=[feature(s) for s in songs[:4]]
    records += [feature(s,'jazz',.5) for s in songs[4:8]]
    stale=feature(songs[8]);stale['mtime']=0;records.append(stale)
    assert a.mixes.import_features(records)==8
    with a.db() as con:a.mixes.put(con,'nextRefresh',0)
    data=a.mixes.feed()
    styles=[m for m in data['mixes'] if m['kind']=='style']
    assert len(styles)==1 and styles[0]['title']=='摇滚'
    assert {s['id'] for s in styles[0]['tracks']}=={s['id'] for s in songs[:4]}
    updated=c.put('/music/api/mixes/config',json={'intervalHours':2}).json()
    assert updated['intervalHours']==2
    assert [m['id'] for m in updated['mixes']]==[m['id'] for m in data['mixes']]
    assert c.put('/music/api/mixes/config',json={'intervalHours':3}).status_code==422
    for m in data['mixes']:
        if m['kind']=='random':
            assert max(sum(s['artist']==artist for s in m['tracks']) for artist in {s['artist'] for s in m['tracks']})<=2


def test_audio_validation_rejects_unverifiable_samples(api):
    _,a=api;r=feature(a.library()[0])
    for change in [{'inputAudioSha256':''},{'sampledSeconds':float('nan')},{'segments':[[0,1]]}]:
        with pytest.raises(ValueError):validate_record({**r,**change})
    assert not validate_record({**r,'music':False})['genres']
    with pytest.raises(ValueError):validate_record({**r,'traits':[{'tag':t,'confidence':1,'atSecond':2} for t in ('vocals','instrumental')]})


def test_audio_prompt_is_anonymous_and_quota_stops_key_retry(api,monkeypatch):
    from server.ai import RateLimited
    _,a=api;song=a.library()[0]
    monkeypatch.setattr(a.traits,'prepare',lambda _: {**feature(song),'creditedArtist':'SECRET ARTIST','audio':b'audio'})
    calls=[]
    def generate(self,prompt,schema,attachments):
        calls.append((prompt,attachments))
        raise RateLimited(time.time()+3600)
    monkeypatch.setattr('server.traits.AIClient.generate',generate)
    with pytest.raises(RuntimeError):a.traits.analyze([song])
    text=calls[0][0]
    assert 'SECRET ARTIST' not in text and song['title'] not in text and song['artist'] not in text
    assert a.mixes.get('audioRetryAt')>time.time()+3500
    with pytest.raises(RuntimeError):a.traits.analyze([song])
    assert len(calls)==1


def test_periodic_analyzer_preserves_quota_cooldown(api,monkeypatch):
    monkeypatch.setenv('AI_API_KEYS','test-key')
    _,a=api
    def limited(songs):
        with a.db() as c:a.mixes.put(c,'audioRetryAt',time.time()+3600)
        raise RuntimeError('quota')
    monkeypatch.setattr(a.traits,'analyze',limited)
    stop=Mock();stop.wait.side_effect=[False,True]
    a.traits.loop(stop)
    assert a.mixes.get('audioRetryAt')>time.time()+3500
