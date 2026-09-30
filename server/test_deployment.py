from datetime import datetime
from zoneinfo import ZoneInfo
from server.test_app import api


def test_landing_health_and_ai_keys_are_not_exposed(api,monkeypatch):
    client,app=api
    monkeypatch.setenv('AI_API_KEYS','super-private-test-key')
    data=client.get('/music/api/health').json()
    assert data['ok'] and data['aiConfigured']
    assert 'super-private' not in str(data)
    html=client.get('/music').text
    assert '繁體中文' in html and 'English' in html
    assert client.get('/').status_code==200


def test_youtube_proxy_and_cookie_file_are_optional(api,monkeypatch):
    _,app=api
    monkeypatch.setenv('YOUTUBE_PROXY_URL','http://proxy.example:7890')
    monkeypatch.setenv('YOUTUBE_COOKIES_FILE','/run/echo/cookies.txt')
    command=app.yt_command()
    assert command[command.index('--proxy')+1]=='http://proxy.example:7890'
    assert command[command.index('--cookies')+1]=='/run/echo/cookies.txt'


def test_analysis_respects_daylight_saving_boundaries(api,monkeypatch):
    client,app=api
    monkeypatch.setattr('server.radio.HK',ZoneInfo('America/New_York'))
    # The spring transition has 23 hours; do not include the next day's first hour.
    from server.test_radio import send
    song=app.library()[0]
    zone=ZoneInfo('America/New_York')
    within=datetime(2026,3,8,12,tzinfo=zone).timestamp()
    next_day=datetime(2026,3,9,0,30,tzinfo=zone).timestamp()
    assert send(client,song['id'],event='within-session',seconds=30,at=within).status_code==200
    assert send(client,song['id'],event='outside-session',seconds=90,at=next_day).status_code==200
    report=app.radio.evidence('2026-03-08')
    assert len(report['today'])==1 and report['today'][0]['seconds']==30
