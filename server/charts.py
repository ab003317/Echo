"""Source-ranked charts and explicitly followed artists; no AI-generated rankings."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
from email.utils import parsedate_to_datetime
import json
import hashlib
import os
import re
import threading
import time
import unicodedata
from urllib.parse import urlencode

import httpx

REGIONS = {'youtube': ('global', 'hk', 'tw', 'jp'), 'apple': ('hk', 'tw', 'jp')}
TTL = 6 * 3600


def identity(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', str(value)).casefold() if c.isalnum())


def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)


def image(value):
    if isinstance(value, str):
        return value if value.startswith('https://') else ''
    thumbs = value.get('thumbnails', []) if isinstance(value, dict) else []
    return image(thumbs[-1].get('url', '')) if thumbs else ''


def youtube_chart(body, region):
    content = next((x['content'] for x in objects(body)
                    if isinstance(x.get('content'), dict) and 'trackTypes' in x['content']), None)
    if not content:
        raise ValueError('Missing chart content')
    params = content.get('perspectiveMetadata', {}).get('requestParams', {}).get('chartParams', {})
    if params.get('countryCode', '').lower() not in ({'global', 'zz'} if region == 'global' else {region}):
        raise ValueError('Chart region mismatch')
    if params.get('chartType') != 'CHART_TYPE_TRACKS' or params.get('chartPeriodType') != 'CHART_PERIOD_TYPE_WEEKLY':
        raise ValueError('Chart type mismatch')
    chart = next((x for x in content['trackTypes'] if x.get('listType') == 'TOP_VIEWS_CHART'
                  and x.get('chartPeriodType') == 'CHART_PERIOD_TYPE_WEEKLY'), None)
    if not chart or not chart.get('endDate'):
        raise ValueError('Missing chart period')
    rows = []
    for entry in chart.get('trackViews', []):
        rank = entry.get('chartEntryMetadata', {}).get('currentPosition')
        if not isinstance(rank, int) or rank < 1 or not entry.get('name'):
            continue
        video = entry.get('atvExternalVideoId') or entry.get('encryptedVideoId', '')
        video = video if re.fullmatch(r'[A-Za-z0-9_-]{11}', video) else ''
        rows.append({'id': str(entry['id']), 'title': entry['name'],
                     'artist': ' / '.join(a['name'] for a in entry.get('artists', []) if a.get('name')),
                     'rank': rank, 'previousRank': entry.get('chartEntryMetadata', {}).get('previousPosition'),
                     'video': video, 'cover': image(entry.get('thumbnail')), 'releaseDate': '',
                     'url': 'https://music.youtube.com/watch?v=' + video if video else ''})
    if not rows or len({x['rank'] for x in rows}) != len(rows):
        raise ValueError('Invalid ranked chart')
    return {'items': sorted(rows, key=lambda x: x['rank']), 'period': chart['endDate'],
            'sourceUrl': f'https://charts.youtube.com/charts/TopSongs/{region}/weekly',
            'provider': 'youtube', 'region': region, 'kind': 'weekly_songs'}


def apple_chart(body, region):
    feed = body['feed']
    if feed.get('country', '').lower() != region:
        raise ValueError('Chart region mismatch')
    rows = [{'id': str(x['id']), 'title': x['name'], 'artist': x['artistName'], 'rank': i + 1,
             'video': '', 'cover': image(x.get('artworkUrl100', '')).replace('100x100bb', '300x300bb'),
             'url': x.get('url', ''), 'releaseDate': x.get('releaseDate', ''),
             'artistId': str(x.get('artistId', ''))}
            for i, x in enumerate(feed['results']) if x.get('kind') == 'songs']
    if not rows:
        raise ValueError('Empty chart')
    # Preserve the source order; an Apple feed timestamp is not a YouTube weekly period.
    return {'items': rows, 'period': parsedate_to_datetime(feed['updated']).isoformat(),
            'sourceUrl': feed['id'], 'provider': 'apple', 'region': region, 'kind': 'most_played'}


class Charts:
    def __init__(self, app):
        self.a = app
        self.lock = threading.Lock()
        self.pending = set()
        self.workers = ThreadPoolExecutor(max_workers=2, thread_name_prefix='catalog')

    def initialize(self):
        with self.a.db() as c:
            c.executescript('''
                CREATE TABLE IF NOT EXISTS catalog_cache(key TEXT PRIMARY KEY,payload TEXT NOT NULL DEFAULT '{}',
                    updated REAL NOT NULL DEFAULT 0,retry_at REAL NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS followed_artists(id TEXT NOT NULL,country TEXT NOT NULL,name TEXT NOT NULL,
                    url TEXT NOT NULL,created REAL NOT NULL,PRIMARY KEY(id,country));
            ''')

    def request(self, url, *, params=None, payload=None, youtube=False):
        # Use the configured YouTube route only for YouTube, not Apple's catalogue.
        proxy = (os.getenv('YOUTUBE_PROXY_URL') or None) if youtube else None
        with httpx.Client(timeout=httpx.Timeout(20, connect=8), proxy=proxy,
                          follow_redirects=True, trust_env=False) as client:
            response = client.post(url, json=payload) if payload is not None else client.get(url, params=params)
            response.raise_for_status()
            return response.json()

    def fetch_chart(self, provider, region):
        if provider == 'apple':
            return apple_chart(self.request(f'https://rss.marketingtools.apple.com/api/v2/{region}/music/most-played/50/songs.json'), region)
        query = urlencode({'perspective': 'CHART_DETAILS', 'chart_params_country_code': 'zz' if region == 'global' else region,
                           'chart_params_chart_type': 'TRACKS', 'chart_params_period_type': 'WEEKLY'})
        body = self.request('https://charts.youtube.com/youtubei/v1/browse?alt=json', youtube=True,
                            payload={'context': {'client': {'clientName': 'WEB_MUSIC_ANALYTICS', 'clientVersion': '2.0', 'hl': 'en', 'gl': 'US'}},
                                     'browseId': 'FEmusic_analytics_charts_home', 'query': query})
        return youtube_chart(body, region)

    def cached(self, key):
        with self.a.db() as c:
            row = c.execute('SELECT * FROM catalog_cache WHERE key=?', (key,)).fetchone()
        return dict(row) if row else {'payload': '{}', 'updated': 0, 'retry_at': 0, 'error': ''}

    def update(self, key, loader):
        try:
            payload = loader()
            with self.a.db() as c:
                c.execute('''INSERT INTO catalog_cache(key,payload,updated) VALUES(?,?,?) ON CONFLICT(key)
                    DO UPDATE SET payload=excluded.payload,updated=excluded.updated,retry_at=0,error='' ''',
                    (key, json.dumps(payload, ensure_ascii=False), time.time()))
        except Exception:
            # Keep the last successful edition. Provider error bodies are not exposed.
            with self.a.db() as c:
                c.execute('''INSERT INTO catalog_cache(key,retry_at,error) VALUES(?,?,'source_unavailable')
                    ON CONFLICT(key) DO UPDATE SET retry_at=excluded.retry_at,error=excluded.error''', (key, time.time()+600))
        finally:
            with self.lock:
                self.pending.discard(key)

    def schedule(self, key, loader, force=False):
        cached = self.cached(key)
        now = time.time()
        next_attempt = (cached['retry_at'] - 600 + (60 if force else 600)
                        if cached['error'] else cached['updated'] + (60 if force else TTL))
        with self.lock:
            if key in self.pending or now < next_attempt:
                return
            self.pending.add(key)
        self.workers.submit(self.update, key, loader)

    def attach_library(self, rows):
        songs = self.a.library()
        matches = {}
        for song in songs:
            matches.setdefault((identity(song['title']), identity(song['artist'])), []).append(song)
        with self.a.db() as c:
            videos = {r['video']: r['track'] for r in c.execute("SELECT video,track FROM jobs WHERE status='complete'")}
        by_id = {s['id']: s for s in songs}
        result = []
        for row in rows:
            same = matches.get((identity(row['title']), identity(row['artist'])), [])
            # Ambiguous versions remain unlinked rather than silently choosing one recording.
            song = by_id.get(videos.get(row.get('video', ''))) or (same[0] if len(same) == 1 else None)
            result.append({**row, 'song': song})
        return result

    def chart(self, provider, region, refresh=False):
        if provider not in REGIONS or region not in REGIONS[provider]:
            raise self.a.HTTPException(404, '此來源未提供該地區榜單 / Chart region unavailable')
        key = f'chart:{provider}:{region}'
        self.schedule(key, lambda: self.fetch_chart(provider, region), refresh)
        cached = self.cached(key)
        payload = json.loads(cached['payload'])
        with self.lock:
            loading = key in self.pending
        return {**payload, 'provider': provider, 'region': region,
                'items': self.attach_library(payload.get('items', [])), 'updated': cached['updated'],
                'stale': bool(cached['error']) or time.time()-cached['updated'] > TTL,
                'loading': loading, 'error': cached['error']}

    def ranking(self, days):
        since = time.time()-days*86400 if days else 0
        with self.a.db() as c:
            rows = c.execute('''SELECT track,COUNT(*) plays,SUM(seconds) seconds,MAX(at) last
                FROM listens WHERE at>=? GROUP BY track ORDER BY plays DESC,seconds DESC,last DESC,track''', (since,)).fetchall()
        songs = {s['id']: s for s in self.a.library()}
        items = []
        for row in rows:
            if row['track'] not in songs:
                continue
            song = songs[row['track']]
            items.append({'id': song['id'], 'title': song['title'], 'artist': song['artist'],
                          'rank': len(items)+1, 'playCount': row['plays'], 'seconds': row['seconds'], 'song': song})
            if len(items) == 100:
                break
        return {'provider': 'echo', 'days': days, 'items': items, 'updated': time.time(), 'loading': False}

    def artists(self):
        with self.a.db() as c:
            return [dict(r) for r in c.execute('SELECT * FROM followed_artists ORDER BY created DESC')]

    def search_artists(self, query, country):
        result = self.request('https://itunes.apple.com/search', params={'term': query, 'entity': 'musicArtist',
                              'country': country, 'limit': 20})
        followed = {(a['id'], a['country']) for a in self.artists()}
        return {'artists': [{'id': str(r['artistId']), 'name': r['artistName'], 'country': country,
                             'url': r.get('artistLinkUrl', ''), 'followed': (str(r['artistId']), country) in followed}
                            for r in result.get('results', []) if r.get('wrapperType') == 'artist']}

    def follow(self, artist, country, enabled):
        if enabled:
            result = self.request('https://itunes.apple.com/lookup', params={'id': artist, 'country': country})
            record = next((r for r in result.get('results', []) if str(r.get('artistId')) == artist and r.get('wrapperType') == 'artist'), None)
            if not record:
                raise self.a.HTTPException(404, '找不到歌手 / Artist unavailable')
            with self.a.db() as c:
                if c.execute('SELECT COUNT(*) FROM followed_artists').fetchone()[0] >= 50 and not c.execute(
                    'SELECT 1 FROM followed_artists WHERE id=? AND country=?', (artist, country)).fetchone():
                    raise self.a.HTTPException(400, '最多關注 50 位歌手 / Follow up to 50 artists')
                c.execute('INSERT OR IGNORE INTO followed_artists VALUES(?,?,?,?,?)',
                          (artist, country, record['artistName'], record.get('artistLinkUrl', ''), time.time()))
            self.schedule(f'artist:{country}:{artist}', lambda: self.fetch_releases(artist, country))
        else:
            with self.a.db() as c:
                c.execute('DELETE FROM followed_artists WHERE id=? AND country=?', (artist, country))
        return {'artists': self.artists()}

    def fetch_releases(self, artist, country):
        result = self.request('https://itunes.apple.com/lookup', params={'id': artist, 'entity': 'song',
                              'country': country, 'limit': 200, 'sort': 'recent'})
        # A missing artist indicates a provider failure, not a confirmed empty discography.
        if not any(str(r.get('artistId')) == artist and r.get('wrapperType') == 'artist' for r in result.get('results', [])):
            raise ValueError('Artist lookup failed')
        tracks = []
        for row in result['results']:
            if row.get('kind') != 'song' or str(row.get('artistId')) != artist:
                continue
            released = str(row.get('releaseDate', ''))[:10]
            try:
                if date.fromisoformat(released) > datetime.now(timezone.utc).date():
                    continue
            except ValueError:
                continue
            tracks.append({'id': str(row['trackId']), 'artistId': artist, 'title': row['trackName'],
                           'artist': row['artistName'], 'album': row.get('collectionName', ''), 'releaseDate': released,
                           'cover': image(row.get('artworkUrl100', '')).replace('100x100bb', '300x300bb'),
                           'url': row.get('trackViewUrl', ''), 'video': '', 'country': country})
        return {'items': tracks}

    def releases(self, refresh=False, offset=0, limit=50):
        artists = self.artists()
        rows, updates, errors, loading = {}, [], [], False
        for artist in artists:
            aid, country = artist['id'], artist['country']
            key = f'artist:{country}:{aid}'
            self.schedule(key, lambda aid=aid, country=country: self.fetch_releases(aid, country), refresh)
            cached = self.cached(key)
            updates.append(cached['updated'])
            if cached['error']:
                errors.append(artist['name'])
            with self.lock:
                loading |= key in self.pending
            for row in json.loads(cached['payload']).get('items', []):
                rows.setdefault(row['id'], row)
        ordered = sorted(rows.values(), key=lambda r: (r['releaseDate'], r['id']), reverse=True)
        page = ordered[offset:offset+limit]
        revision = hashlib.sha256(json.dumps([(a['country'], a['id'], updated)
            for a, updated in zip(artists, updates)]).encode()).hexdigest()[:20]
        return {'items': self.attach_library(page), 'artists': artists, 'updated': min(updates) if updates else 0,
                'revision': revision,
                'errors': errors, 'loading': loading, 'hasMore': offset+limit < len(ordered), 'nextOffset': offset+len(page),
                'source': 'apple_catalog'}

    def loop(self, stop):
        while not stop.wait(30):
            try:
                for provider, regions in REGIONS.items():
                    for region in regions:
                        self.schedule(f'chart:{provider}:{region}', lambda p=provider, r=region: self.fetch_chart(p, r))
                self.releases()
            except Exception:
                pass
            if stop.wait(270):
                break
        self.workers.shutdown(wait=False, cancel_futures=True)
