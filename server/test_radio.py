import json
import time
import hashlib
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from server.test_app import api
from server.radio import HK
from server.gemini import POLICY_VERSION


def pool(a, state='explore', origin='pool'):
    song = a.library()[0]
    with a.db() as c:
        c.execute("INSERT INTO jobs(id,video,title,status,created,origin,visible,track) VALUES('job','abcdefghijk','Song','complete',?,?,0,?)", (time.time(), origin, song['id']))
        c.execute('INSERT INTO pool_entries(video,track,state,created,metadata) VALUES(?,?,?,?,?)', ('abcdefghijk', song['id'], state, time.time()-172800, json.dumps(song)))
    return song['id']


def send(c, tid, event='session-one', seconds=1, seq=1, outcome='progress', at=None):
    at = at or time.time()
    return c.post('/music/api/playback', json=dict(event=event, track=tid, seconds=seconds, seq=seq, outcome=outcome, position=seconds, duration=40, started=at-100, at=at))


def test_event_upsert_retention_and_resident(api):
    c,a=api
    tid=pool(a)
    assert send(c,tid).status_code==200
    assert a.library()[0]['pool']=='kept'
    send(c,tid,seconds=25,seq=2)
    send(c,tid,seconds=2,seq=1)
    send(c,tid,seconds=40,seq=3,outcome='completed')
    send(c,tid,seconds=100,seq=4)
    assert a.library()[0]['plays']==1
    with a.db() as con:
        row=con.execute('SELECT * FROM playback_sessions').fetchone()
        assert row['seconds']==40 and row['outcome']=='completed'
    for n in range(2): send(c,tid,event=f'another-session-{n}',seconds=35)
    assert a.library()[0]['pool']=='resident'


def test_ready_only_actual_files_and_exclusion(api):
    c,a=api
    tid=a.library()[0]['id']
    first=c.post('/music/api/discover',json={'seed':'ready'}).json()
    assert first['tracks'][0]['song']['id']==tid
    second=c.post('/music/api/discover',json={'seed':'ready','page':1,'exclude':[tid]}).json()
    assert second['tracks']==[] and second['retryAfter']==30
    (a.ROOT/'Artist_Song.wav').unlink()
    assert c.post('/music/api/discover',json={}).json()['tracks']==[]


def test_rotation_keeps_heard_and_original_files(api):
    c,a=api
    tid=pool(a)
    now=time.time()
    a.radio.rotate('2026-10-01',now)
    assert a.library()[0]['pool']=='retiring'
    a.radio.cleanup(now+4*86400)
    assert (a.ROOT/'Artist_Song.wav').exists()  # Not an owned auto-download path.
    send(c,tid,seconds=2,outcome='skipped')
    a.radio.rotate('2026-10-02',now+86400)
    assert a.library()[0]['pool']=='kept'


def test_rotated_tracks_leave_recommendations_but_remain_playable_and_can_be_rescued(api):
    c,a=api
    tid=pool(a,'retiring')
    assert a.library()[0]['id']==tid
    assert a.find_track(tid)[0]['id']==tid
    assert a.recommendations()==[]
    assert c.post('/music/api/discover',json={}).json()['tracks']==[]
    send(c,tid,seconds=5,outcome='stopped')
    assert a.recommendations()[0]['id']==tid


def test_unheard_auto_downloads_do_not_seed_future_exploration(api):
    c,a=api
    tid=pool(a)
    assert not any(query.startswith('Artist ') for query in a.discovery_queries('seed','new',use_ai=False))
    send(c,tid,seconds=35,outcome='stopped')
    assert any(query.startswith('Artist ') for query in a.discovery_queries('seed','new',use_ai=False))


def test_concurrent_import_claims_are_unique_and_honor_pause_and_manual_priority(api):
    c,a=api
    a.radio.queue_candidates([{'id':f'v{i:010d}','title':'auto'} for i in range(12)],12)
    c.post('/music/api/imports',json={'url':'abcdefghijk'})
    a.radio.configure({'enabled':False})
    assert a.claim_import_job()['video']=='abcdefghijk'
    assert a.claim_import_job() is None
    a.radio.configure({'enabled':True})
    with ThreadPoolExecutor(max_workers=4) as workers:
        claimed=list(workers.map(lambda _:a.claim_import_job(),range(16)))
    videos=[job['video'] for job in claimed if job]
    assert len(videos)==len(set(videos))==12


def test_concurrent_publication_cannot_overrun_pool_budget(api,tmp_path):
    c,a=api
    a.radio.queue_candidates([{'id':f'v{i:010d}','title':'auto'} for i in range(2)],2)
    a.radio.put('config',{'budgetBytes':8})
    source=tmp_path/'sample.m4a';source.write_bytes(b'12345')
    jobs=[a.claim_import_job(),a.claim_import_job()]
    with ThreadPoolExecutor(max_workers=2) as workers:
        futures=[workers.submit(a.publish_import,job,source,True) for job in jobs]
    assert sum(f.exception() is None for f in futures)==1
    assert sum(isinstance(f.exception(),RuntimeError) for f in futures)==1
    assert a.radio.used()==5
    assert len(list((a.ROOT/'Serein Discoveries').glob('*.m4a')))==1


def test_cleanup_owned_only_and_offline_rescue(api):
    c,a=api
    tid=pool(a,'retiring')
    folder=a.ROOT/'Serein Discoveries';folder.mkdir()
    target=folder/'abcdefghijk.m4a';target.write_bytes(b'owned')
    with a.db() as con:
        con.execute('UPDATE tracks SET path=? WHERE id=?', ('Serein Discoveries/abcdefghijk.m4a',tid))
        con.execute('UPDATE pool_entries SET retire_at=?', (time.time()-1,))
    a.radio.cleanup()
    assert not target.exists() and not a.library()
    assert send(c,tid,seconds=5,outcome='stopped').status_code==200
    with a.db() as con:
        assert con.execute('SELECT state FROM pool_entries').fetchone()[0]=='kept'
        assert con.execute('SELECT status FROM jobs').fetchone()[0]=='queued'


def test_leases_favorites_manual_adoption(api):
    c,a=api
    tid=pool(a)
    assert c.get('/music/api/imports').json()['jobs']==[]
    a.radio.access(tid)
    a.radio.rotate('2026-10-01')
    assert a.library()[0]['pool']=='explore'
    c.put(f'/music/api/tracks/{tid}/favorite',json={'liked':True,'updated':time.time()})
    assert a.library()[0]['pool']=='resident'
    c.post('/music/api/imports',json={'url':'abcdefghijk'})
    assert len(c.get('/music/api/imports').json()['jobs'])==1


def test_evidence_does_not_confuse_errors_with_skips(api):
    c,a=api
    tid=a.library()[0]['id']
    for i,outcome in enumerate(['error','stopped','skipped','completed']):
        send(c,tid,event=f'evidence-session-{i}',seconds=40 if outcome=='completed' else 2,outcome=outcome)
    data=a.radio.evidence(datetime.now(HK).date().isoformat())['today'][0]
    assert data['earlySkips']==1 and data['completed']==1 and data['effectivePlays']==1
    assert data['sessions']==4 and data['seconds']==46


def test_pool_validation_and_budget(api,monkeypatch):
    c,a=api
    assert c.put('/music/api/pool',json={'target':10000}).status_code==422
    assert c.put('/music/api/pool',json={'dailyPercent':100}).status_code==422
    monkeypatch.setattr(a.radio,'used',lambda:3*1073741824)
    monkeypatch.setattr(a,'search_youtube',lambda *args,**kw: (_ for _ in ()).throw(AssertionError('Budget must stop searches')))
    a.radio.fill()
    assert a.radio.status()['pending']==0


def test_fill_mixes_ai_and_exploration(api,monkeypatch):
    c,a=api
    a.radio.put('profile',{'policyVersion':POLICY_VERSION,'queries':['personal-a','personal-b','personal-c']})
    monkeypatch.setattr(a,'discovery_queries',lambda *args,**kw:['explore'])
    seen=[]
    def search(query,**kw):
        seen.append(query)
        return ([{'id':f'{len(seen)}{i:010d}','title':query,'duration':180} for i in range(18)],True)
    monkeypatch.setattr(a,'search_youtube',search)
    a.radio.fill()
    with a.db() as con:
        rows=con.execute('SELECT title,COUNT(*) FROM jobs GROUP BY title').fetchall()
    allocation={r[0]:r[1] for r in rows}
    assert sum(allocation.values())==8 and allocation['explore']>=2
    assert sum(value for key,value in allocation.items() if key.startswith('personal'))==6


def test_parallel_search_keeps_provenance_and_other_sources_when_one_fails(api,monkeypatch):
    c,a=api
    a.radio.put('profile',{'policyVersion':POLICY_VERSION,'queries':['broken','broken']})
    a.seeds.import_csv('Video ID,Song Title,Album Title,Artist Name 1\nabcdefghijk,Song,Album,A\n','songs.csv')
    monkeypatch.setattr(a,'discovery_queries',lambda *args,**kw:['LibraryArtist new release official audio'])
    barrier=threading.Barrier(3)
    def search(query,**kw):
        barrier.wait(timeout=3)
        if query=='broken':raise RuntimeError('source temporarily unavailable')
        prefix=hashlib.sha256(query.encode()).hexdigest()[:4]
        return ([{'id':prefix+f'{i:07d}','title':query} for i in range(18)],True)
    monkeypatch.setattr(a,'search_youtube',search)
    a.radio.fill()
    with a.db() as con:
        sources=[json.loads(r[0]) for r in con.execute('SELECT metadata FROM pool_entries')]
    assert sources and len(sources)<=8
    assert all(s['discoveryQuery'] for s in sources)
    assert any(s['expectedArtist']=='A' for s in sources)
    assert any(s['expectedArtist']=='LibraryArtist' for s in sources)


def test_rotation_once_and_grace_period(api):
    c,a=api
    tid=pool(a)
    now=time.time()
    a.radio.rotate('2026-10-01',now)
    with a.db() as con:
        until=con.execute('SELECT retire_at FROM pool_entries').fetchone()[0]
    assert until==now+72*3600
    a.radio.rotate('2026-10-01',now+3600)
    with a.db() as con:assert con.execute('SELECT retire_at FROM pool_entries').fetchone()[0]==until


def test_legacy_playback_and_new_outcomes_share_one_count(api):
    c,a=api
    tid=pool(a)
    c.post('/music/api/listens',json={'event':'session-one','track':tid,'seconds':30,'at':time.time()})
    send(c,tid,seconds=40,outcome='completed')
    assert a.library()[0]['plays']==1
    assert a.radio.evidence(datetime.now(HK).date().isoformat())['legacy']==[]


def test_analysis_can_continue_with_downloads_paused(api,monkeypatch):
    monkeypatch.setenv('AI_API_KEYS','test-key')
    c,a=api
    a.radio.configure({'enabled':False})
    days=[]
    monkeypatch.setattr(a.radio,'analyze',lambda day:days.append(day))
    monkeypatch.setattr(a.radio,'fill',lambda: (_ for _ in ()).throw(AssertionError('Downloads are paused')))
    a.radio.tick()
    assert len(days)==1


def test_old_unverified_summary_is_invalidated(api):
    c,a=api
    a.radio.put('profile',{'summary':'Title implies genre','queries':['a','b','c']})
    assert a.radio.profile()=={}
    assert c.get('/music/api/pool').json()['analysis']['summary']==''
