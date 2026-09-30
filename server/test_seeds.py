import json
import pytest
from server.seeds import parse_export, weights, artist_matches
from server.test_app import api
from server.gemini import analysis_input, grounded_profile


CSV = '影片 ID,Song Title,Album Title,Artist Name 1,Artist Name 2\n -yGI2uFaacY,Song,Album,A,B\nabcdefghijk,Other,Album,A,\n'


def test_artist_identity_uses_explicit_credits_not_partial_names_or_unverified_channels():
    assert not artist_matches('KAF',{'artist':'Kaf Inah','channel':'Kaf Inah','channel_is_verified':True})
    assert not artist_matches('RIM',{'artist':'Pacific Rim'})
    assert artist_matches('KAF',{'channel':'花譜 -KAF-','channel_is_verified':True})
    assert artist_matches('Reol',{'channel':'Reol Official','channel_is_verified':True})
    assert artist_matches('MYTH & ROID',{'artist':'MYTH & ROID'})
    assert artist_matches('Mili',{'artist':'Binary Haze Interactive / Mili'})
    assert not artist_matches('Mili',{'channel':'Mili','channel_is_verified':False})


def test_unrelated_music_is_rejected_before_audio_download_for_an_artist_query(api,monkeypatch):
    c,a=api
    a.radio.queue_candidates([{'id':'abcdefghijk','title':'Music','expectedArtist':'KAF'}],1)
    a.quality.record('abcdefghijk','approved','music',{'artist':'Kaf Inah','channel_is_verified':True})
    monkeypatch.setattr(a,'yt_command',lambda: (_ for _ in ()).throw(AssertionError('Wrong artist must not download')))
    a.import_job(a.claim_import_job())
    with a.db() as con:
        assert con.execute('SELECT status FROM jobs').fetchone()[0]=='rejected'
        assert con.execute('SELECT status FROM content_checks').fetchone()[0]=='approved'
    assert a.radio.used()==0


def test_export_normalizes_whitespace_and_deduplicates_ids_without_inventing_likes():
    rows, dup = parse_export(('\ufeff'+CSV+'abcdefghijk,Other,Album,A,\n').encode())
    assert len(rows) == 2 and dup == 1 and rows[0]['video'] == '-yGI2uFaacY'
    assert rows[0]['artists'] == ['A','B']
    assert not {'plays','favorite','liked'} & set(rows[0])
    with pytest.raises(ValueError):
        parse_export(CSV.replace('abcdefghijk','invalid'))


def test_full_album_and_collaborator_credits_are_balanced():
    rows = [{'video':str(i),'title':str(i),'album':'bulk','artists':['A']} for i in range(100)]
    rows += [{'video':'b'+str(i),'title':str(i),'album':str(i),'artists':['B']} for i in range(10)]
    scores = {r['artist']:r for r in weights(rows)}
    assert scores['A']['albumBalancedCount'] == scores['B']['albumBalancedCount'] == 10
    joint = weights([{'video':'c','title':'Song','album':'','artists':['A','B']}])
    assert [r['albumBalancedCount'] for r in joint] == [.5,.5]


def test_seed_import_is_idempotent_and_separate_from_user_history(api):
    c,a = api
    for _ in range(2):
        assert a.seeds.import_csv(CSV,'songs.csv')['tracks'] == 2
    with a.db() as con:
        assert con.execute('SELECT COUNT(*) FROM preference_seeds').fetchone()[0] == 2
        for table in ('favorites','listens','playback_sessions','jobs'):
            assert con.execute('SELECT COUNT(*) FROM '+table).fetchone()[0] == 0
    evidence = a.radio.evidence('2026-09-30')
    assert len(evidence['external']) == 2 and evidence['favorites'] == []
    safe = analysis_input(evidence)
    assert all(not {'title','album','lyrics'} & set(r) for r in safe['external'])
    assert safe['musicEvidence']['audioAnalyzed'] is False


def test_external_direction_cannot_become_favorite_or_strong_artist_weight(api):
    c,a = api
    a.seeds.import_csv(CSV,'songs.csv')
    evidence = a.radio.evidence('2026-09-30')
    raw = {'observations':[{'kind':'seed','period':'external','track':'yt:abcdefghijk'}],
           'artists':[{'artist':'A','weight':.99},{'artist':'C','weight':.99}],
           'queries':['A official audio','B official audio','C official audio'],'exploration':.3}
    profile = grounded_profile(raw,evidence)
    assert profile['artists'] == [{'artist':'A','weight':.45}]
    assert '2 首外部口味参考' in profile['summary'] and '温和的推荐方向' in profile['summary']
    raw['observations'][0]['kind'] = 'favorite'
    with pytest.raises(ValueError):
        grounded_profile(raw,evidence)


def test_only_matching_audio_samples_with_provenance_and_supported_timestamps_enter_evidence(api):
    c,a = api
    a.seeds.import_csv(CSV,'songs.csv')
    record = {'video':'abcdefghijk','music':True,'sampledSeconds':60,'segments':[[10,20],[40,20],[70,20]],
              'model':'test-model','inputAudioSha256':'a'*64,'traits':[
                  {'tag':'piano','confidence':.9,'atSecond':30},
                  {'tag':'synth','confidence':.4,'atSecond':20},
                  {'tag':'drum_kit','confidence':.9,'atSecond':800}]}
    assert a.seeds.import_audio([record,{**record,'video':'not-in-seeds'}]) == 1
    safe = analysis_input(a.radio.evidence('2026-09-30'))
    assert safe['musicEvidence']['audioAnalyzed'] is True
    assert safe['musicEvidence']['verifiedMusicalFeatures'] == []
    assert safe['musicEvidence']['audioSamples'][0]['traits'] == [{'tag':'piano','confidence':.9,'atSecond':30}]
    assert a.seeds.import_audio([record]) == 1
    assert len(a.seeds.audio_evidence()) == 1
