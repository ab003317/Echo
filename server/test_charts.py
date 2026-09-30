import json
import time
from datetime import datetime, timezone

import pytest

from .charts import youtube_chart, apple_chart
from .test_app import api


def youtube_fixture(region='hk'):
    return {'content': {'perspectiveMetadata': {'requestParams': {'chartParams': {
        'countryCode': region, 'chartType': 'CHART_TYPE_TRACKS', 'chartPeriodType': 'CHART_PERIOD_TYPE_WEEKLY'}}},
        'trackTypes': [{'listType': 'TOP_VIEWS_CHART', 'chartPeriodType': 'CHART_PERIOD_TYPE_WEEKLY', 'endDate': '2026-09-24',
            'trackViews': [{'id': 'G:a', 'name': 'First', 'artists': [{'name': 'Artist'}],
                'encryptedVideoId': 'abcdefghijk', 'chartEntryMetadata': {'currentPosition': 2, 'previousPosition': 5}},
                {'id': 'G:b', 'name': 'Second', 'artists': [{'name': 'Other'}],
                'atvExternalVideoId': '123456789ab', 'chartEntryMetadata': {'currentPosition': 1}}]}]}}


def test_youtube_preserves_real_ranks_period_and_region():
    chart = youtube_chart(youtube_fixture(), 'hk')
    assert [x['id'] for x in chart['items']] == ['G:b', 'G:a']
    assert chart['period'] == '2026-09-24'
    assert chart['items'][1]['previousRank'] == 5
    assert chart['items'][0]['video'] == '123456789ab'
    with pytest.raises(ValueError):
        youtube_chart(youtube_fixture('global'), 'hk')
    with pytest.raises(ValueError):
        youtube_chart({'error': 'changed schema'}, 'hk')
    broken = youtube_fixture()
    broken['content']['trackTypes'][0]['trackViews'][0]['chartEntryMetadata']['currentPosition'] = 1
    with pytest.raises(ValueError):
        youtube_chart(broken, 'hk')


def test_apple_preserves_feed_order_without_inventing_global():
    feed = {'feed': {'country': 'jp', 'id': 'https://rss.marketingtools.apple.com/api/v2/jp/music/most-played/50/songs.json',
        'updated': 'Wed, 30 Sep 2026 19:00:43 +0000', 'results': [
        {'id': '2', 'name': 'Second alphabetically', 'artistName': 'B', 'kind': 'songs'},
        {'id': '1', 'name': 'Alphabetically first', 'artistName': 'A', 'kind': 'songs'}]}}
    assert [r['id'] for r in apple_chart(feed, 'jp')['items']] == ['2', '1']
    with pytest.raises(ValueError):
        apple_chart(feed, 'global')


def test_cache_keeps_last_good_edition_and_retries_later(api, monkeypatch):
    c, a = api
    key = 'chart:youtube:hk'
    a.charts.update(key, lambda: youtube_chart(youtube_fixture(), 'hk'))
    before = a.charts.cached(key)
    def unavailable():
        raise RuntimeError('private upstream response must not reach users')
    a.charts.update(key, unavailable)
    after = a.charts.cached(key)
    assert after['payload'] == before['payload'] and after['updated'] == before['updated']
    assert after['retry_at'] > time.time() and after['error'] == 'source_unavailable'
    monkeypatch.setattr(a.charts, 'schedule', lambda *args, **kwargs: None)
    response = c.get('/music/api/charts/youtube/hk').json()
    assert response['stale'] and len(response['items']) == 2
    assert c.get('/music/api/charts/apple/global').status_code == 404
    assert c.get('/music/api/charts/untrusted/hk').status_code == 404


def test_echo_uses_qualifying_plays_not_sessions_and_respects_period(api):
    c, a = api
    song = a.library()[0]
    now = time.time()
    with a.db() as db:
        db.executemany('INSERT INTO listens VALUES(?,?,?,?)', [('old', song['id'], 40, now-9*86400),
            ('new', song['id'], 35, now)])
        db.execute('INSERT INTO playback_sessions VALUES(?,?,?,?,?,?,?,?,?)', ('skip', song['id'], 2, 2, 40, now, now, 'skipped', 1))
    seven = c.get('/music/api/charts/echo?days=7').json()
    thirty = c.get('/music/api/charts/echo?days=30').json()
    assert seven['items'][0]['playCount'] == 1
    assert thirty['items'][0]['playCount'] == 2
    assert c.get('/music/api/charts/echo?days=3').status_code == 422
    assert c.get('/music/api/charts/echo?days=0').json()['items'][0]['playCount'] == 2


def test_followed_artist_identity_dates_pagination_and_unfollow(api, monkeypatch):
    c, a = api
    def request(url, **kwargs):
        artist = {'wrapperType': 'artist', 'artistId': 123, 'artistName': 'Verified Artist'}
        if not kwargs['params'].get('entity'):
            return {'results': [artist]}
        rows = [artist]
        for tid, release, aid in [(1, '2026-01-01', 123), (2, '2025-01-01', 123), (3, '2099-01-01', 123),
                                   (4, '2026-02-01', 999), (5, 'invalid', 123)]:
            rows.append({'kind': 'song', 'trackId': tid, 'trackName': str(tid), 'artistId': aid,
                         'artistName': 'Verified Artist', 'releaseDate': release})
        return {'results': rows}
    monkeypatch.setattr(a.charts, 'request', request)
    monkeypatch.setattr(a.charts, 'schedule', lambda key, loader, *args: a.charts.update(key, loader))
    assert c.put('/music/api/artists/123', json={'country': 'jp', 'followed': True}).status_code == 200
    assert c.put('/music/api/artists/123', json={'country': 'jp', 'followed': True}).status_code == 200
    assert len(a.charts.artists()) == 1
    monkeypatch.setattr(a.charts, 'schedule', lambda *args: None)
    first = c.get('/music/api/releases?limit=1').json()
    assert first['items'][0]['id'] == '1' and first['hasMore']
    second = c.get('/music/api/releases?limit=1&offset=1').json()
    assert second['items'][0]['id'] == '2' and not second['hasMore']
    assert first['revision'] == second['revision']
    a.charts.update('artist:jp:123', lambda: {'items': []})
    assert c.get('/music/api/releases').json()['revision'] != first['revision']
    assert c.put('/music/api/artists/123', json={'country': 'jp', 'followed': False}).status_code == 200
    assert c.get('/music/api/releases').json()['items'] == []
    assert c.put('/music/api/artists/not-an-id', json={'country': 'jp', 'followed': True}).status_code == 422
    assert c.put('/music/api/artists/123', json={'country': 'xx', 'followed': True}).status_code == 422


def test_release_refresh_does_not_erase_unavailable_artist(api, monkeypatch):
    c, a = api
    def missing(*args, **kwargs):
        return {'results': []}
    monkeypatch.setattr(a.charts, 'request', missing)
    with pytest.raises(ValueError):
        a.charts.fetch_releases('123', 'jp')
    assert c.put('/music/api/artists/123', json={'country': 'jp', 'followed': True}).status_code == 404


def test_library_link_requires_exact_unambiguous_metadata(api):
    _, a = api
    song = a.library()[0]
    rows = a.charts.attach_library([{'id': 'external', 'title': song['title'], 'artist': song['artist']},
        {'id': 'wrong', 'title': song['title'], 'artist': 'Different artist'}])
    assert rows[0]['song']['id'] == song['id'] and rows[1]['song'] is None
    with a.db() as c:
        c.execute('INSERT INTO tracks SELECT ?,?,title,artist,album,duration,size,mtime,added,genre,cover FROM tracks LIMIT 1',
                  ('duplicate', 'another.wav'))
    assert a.charts.attach_library([{'id': 'external', 'title': song['title'], 'artist': song['artist']}])[0]['song'] is None


def test_schedule_deduplicates_concurrent_refreshes(api, monkeypatch):
    _, a = api
    submitted = []
    monkeypatch.setattr(a.charts.workers, 'submit', lambda *args: submitted.append(args))
    a.charts.schedule('chart:youtube:hk', lambda: {})
    a.charts.schedule('chart:youtube:hk', lambda: {}, force=True)
    assert len(submitted) == 1


def test_failed_refresh_can_retry_without_waiting_full_cache_ttl(api, monkeypatch):
    _, a = api
    key = 'chart:youtube:hk'
    now = time.time()
    with a.db() as c:
        c.execute('INSERT INTO catalog_cache(key,updated,retry_at,error) VALUES(?,?,?,?)',
                  (key, now-180, now+500, 'source_unavailable'))
    submitted = []
    monkeypatch.setattr(a.charts.workers, 'submit', lambda *args: submitted.append(args))
    a.charts.schedule(key, lambda: {})
    assert not submitted
    a.charts.schedule(key, lambda: {}, force=True)
    assert len(submitted) == 1
    a.charts.pending.clear()
    with a.db() as c:
        c.execute('UPDATE catalog_cache SET retry_at=? WHERE key=?', (now-1, key))
    a.charts.schedule(key, lambda: {})
    assert len(submitted) == 2
