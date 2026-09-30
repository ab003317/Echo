import json
from server.listening_report import build_report, period_metrics
from server.gemini import POLICY_VERSION
from server.test_app import api


def track(**changes):
    return dict(track='a', title='A title that says rock', artist='Artist', sessions=3,
                seconds=230, effectivePlays=2, completed=1, earlySkips=1, **changes)


def test_overlapping_periods_and_imports_are_not_counted_as_extra_listens():
    row=track()
    report=build_report({'today':[row], 'recent':[row],
        'external':[{'track':'yt:x','signal':'direction'}], 'seedArtists':[{'artist':'Seed','weight':.4}]},
        {'artists':[{'artist':'Artist','weight':.6},{'artist':'Seed','weight':.4}]})
    assert report['periods']['today']['effectivePlays']==2
    assert report['periods']['recent']['effectivePlays']==2
    assert len(report['signals'])==1 and report['signals'][0]['kind']=='mixed'
    assert report['changes'][0]['count']==2
    assert report['changes'][1]['basis']=='reference'
    assert report['sources']['favorites']==0
    assert report['audioFeatures']==[]  # title cannot become an audio/genre observation


def test_single_skip_cannot_justify_ai_downweighting_and_unknown_artist_is_omitted():
    report=build_report({'recent':[track()]},{'artists':[
        {'artist':'Artist','weight':-.3},{'artist':'No evidence','weight':1}]})
    assert report['changes']==[]


def test_audio_requires_provenance_timestamp_confidence_and_deduplicates_tracks():
    audio={'track':'yt:1','source':'model_inference_from_audio_excerpts','sampledSeconds':60,
           'segments':[[1,20],[50,20],[100,20]],'traits':[
               {'tag':'piano','confidence':.85,'atSecond':12},
               {'tag':'piano','confidence':.9,'atSecond':21},
               {'tag':'fast_pulse','confidence':.79,'atSecond':10},
               {'tag':'synth','confidence':.95,'atSecond':600}]}
    report=build_report({'audioSamples':[audio,audio,{**audio,'track':'unverified','source':'title_guess'}]}, {})
    assert report['sources']['audioTracks']==1
    assert report['sources']['audioSeconds']==60
    assert report['audioFeatures']==[{'tag':'piano','tracks':1}]


def test_old_capped_snapshot_is_labelled_and_full_totals_are_preserved():
    rows=[{**track(),'track':str(i)} for i in range(71)]
    assert build_report({'today':rows[:70]}, {})['periods']['today']['limited']
    result=build_report({'today':rows[:70],'totals':{'today':period_metrics(rows,complete=True)}}, {})
    assert result['periods']['today']['tracks']==71
    assert result['periods']['today']['effectivePlays']==142
    assert not result['periods']['today']['limited']


def test_pool_report_uses_saved_evidence_and_read_does_not_reanalyze(api,monkeypatch):
    c,a=api
    profile={'policyVersion':POLICY_VERSION,'artists':[{'artist':'Artist','weight':.5}], 'queries':['Artist official audio']}
    evidence={'day':'2026-09-30','today':[track()],'recent':[track()]}
    with a.db() as con:
        con.execute('INSERT INTO taste_reports VALUES(?,?,?,?,?)',('2026-09-30',json.dumps(profile),json.dumps(evidence),'test',1))
    a.radio.put('analysisDay','2026-09-30');a.radio.put('profile',profile)
    monkeypatch.setattr(a.radio,'evidence',lambda *_: (_ for _ in ()).throw(AssertionError('must not use current evidence')))
    report=c.get('/music/api/pool').json()['analysis']['report']
    assert report['day']=='2026-09-30' and report['periods']['today']['seconds']==230
    assert report['changes'][0]['basis']=='qualified'
    a.radio.put('profile',{'policyVersion':0})
    assert c.get('/music/api/pool').json()['analysis']['report'] is None


def test_unknown_duration_cannot_support_high_completion_claim(api):
    from datetime import datetime
    import time
    from server.radio import HK
    c,a=api
    tid=a.library()[0]['id'];now=time.time()
    with a.db() as con:con.execute('UPDATE tracks SET duration=0 WHERE id=?',(tid,))
    response=c.post('/music/api/playback',json=dict(event='unknown-duration-test',track=tid,
        seconds=35,position=35,duration=0,started=now-35,at=now,outcome='completed',seq=1))
    assert response.status_code==200
    evidence=a.radio.evidence(datetime.fromtimestamp(now,HK).date().isoformat())
    assert evidence['today'][0]['effectivePlays']==1
    assert evidence['today'][0]['completed']==0
    assert evidence['totals']['today']['completed']==0


def test_untagged_audio_still_has_measured_duration(api):
    _,a=api
    assert a.library()[0]['duration']==40
