"""Song-to-song neighbours from YouTube Music radios, seeded by the owner's list and real listening.

Artist names alone cannot reach songs whose artist appears once in the owner's list, so candidates
come from the radios of concrete songs instead. Radios are cached; ranking only reads the cache.
"""
from __future__ import annotations
import json
import math
import os
import random
import re
import threading
import time
import unicodedata
from collections import Counter

REFRESH_SECONDS = 7 * 86400
RETRY_SECONDS = 86400
BATCH = 8
DECAY = 5
GENERIC_SHARE = .4
# YouTube Music appends unrelated promotions after the requested 50 radio songs.
RADIO_LENGTH = 50
TRUSTED_TYPES = {'MUSIC_VIDEO_TYPE_ATV', 'MUSIC_VIDEO_TYPE_OMV'}
CREDIT_SPLIT = re.compile(r'\s*/\s*|\s*,\s*|\s*、\s*|\s*&\s*|\s*×\s*|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+and\s+|\s+-\s+|[()（）\[\]【】|]', re.I)
CHANNEL_WORDS = re.compile(r'\b(?:official|youtube|channel|vevo|topic)\b|公式|オフィシャル', re.I)


def text_key(value):
    value = unicodedata.normalize('NFKC', str(value or '')).casefold()
    return ''.join(ch for ch in value if ch.isalnum())


def title_key(title):
    """Base title: YouTube Music appends ' - English Title' and bracketed version notes."""
    return text_key(re.split(r'\s+-\s+|\s*[(（\[【]', unicodedata.normalize('NFKC', str(title or '')))[0])


def artist_keys(artists):
    """Credit keys that survive channel suffixes and collaboration separators."""
    labels = [artists] if isinstance(artists, str) else list(artists or [])
    keys = set()
    for label in labels:
        for part in [label] + CREDIT_SPLIT.split(str(label or '')):
            key = text_key(CHANNEL_WORDS.sub('', unicodedata.normalize('NFKC', str(part or ''))))
            if key and key != 'unknown' and key != '未知歌手':
                keys.add(key)
    return keys


def song_keys(title, artists, video=''):
    keys = {('id', video)} if video else set()
    base = title_key(title)
    if len(base) >= 2:
        keys |= {('song', base, artist) for artist in artist_keys(artists)}
    return keys


def healthy(seed, tracks):
    # Unplayable or unknown seeds come back as a generic feed that no longer starts with the seed.
    return seed in [t.get('id') for t in tracks[:3]]


def neighbours(radios, weights, known=frozenset(), decay=DECAY, generic_share=GENERIC_SHARE):
    """Rank songs by how many weighted seed radios contain them, earlier positions counting more."""
    lists = {seed: tracks[:RADIO_LENGTH] for seed, tracks in radios.items()}
    # Promotion slots fill unplayable seeds' radios and recur across many others; they say nothing
    # about similarity. Every fetched radio, usable or not, helps reveal them.
    spread = Counter(v for tracks in lists.values() for v in {t.get('id') for t in tracks})
    generic = {v for v, n in spread.items() if len(lists) >= 10 and n > generic_share * len(lists)}
    usable = {seed: tracks for seed, tracks in lists.items() if weights.get(seed, 0) > 0 and healthy(seed, tracks)
              and sum(t.get('id') in generic for t in tracks) <= len(tracks) / 2}
    scores, support, info = Counter(), Counter(), {}
    for seed, tracks in usable.items():
        seen = set()
        for position, track in enumerate(tracks):
            video = track.get('id')
            if not video or video == seed or video in generic or video in seen:
                continue
            seen.add(video)
            if known and song_keys(track.get('title'), track.get('artists'), video) & known:
                continue
            scores[video] += weights[seed] / (1 + position / decay)
            support[video] += 1
            info[video] = track
    return [{**info[v], 'score': round(score, 4), 'support': support[v]} for v, score in scores.most_common()]


class Similar:
    def __init__(self, app):
        self.a = app
        self.lock = threading.Lock()
        self.cached = (None, [])
        self.status = ''

    def initialize(self):
        with self.a.db() as c:
            c.executescript('''
                CREATE TABLE IF NOT EXISTS similar_radios(video TEXT PRIMARY KEY,fetched REAL NOT NULL,
                    status TEXT NOT NULL,tracks TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS similar_lookups(track TEXT PRIMARY KEY,video TEXT NOT NULL,checked REAL NOT NULL);
            ''')

    def client(self):
        from ytmusicapi import YTMusic
        proxy = os.getenv('YOUTUBE_PROXY_URL')
        return YTMusic(proxies={'http': proxy, 'https': proxy} if proxy else None)

    def videos(self, c):
        """Library track -> YouTube video, from pool provenance, imports and title lookups."""
        rows = c.execute("""SELECT track,video FROM similar_lookups WHERE video!='' UNION ALL
            SELECT track,video FROM jobs WHERE track!='' UNION ALL
            SELECT track,video FROM pool_entries WHERE track!=''""").fetchall()
        return {r[0]: r[1] for r in rows}

    def seeds(self):
        """The owner's list counts once per song; songs enjoyed in Echo count more."""
        weights = {row['video']: 1.0 for row in self.a.seeds.rows()}
        lookups = []
        excluded = self.a.quality.excluded()
        with self.a.db() as c:
            videos = self.videos(c)
            looked = {r[0] for r in c.execute('SELECT track FROM similar_lookups')}
            rows = c.execute('''SELECT t.id,t.title,t.artist,t.duration,COALESCE(f.liked,0) liked,
                (SELECT COUNT(*) FROM listens l WHERE l.track=t.id) plays,
                (SELECT COUNT(*) FROM playback_sessions p WHERE p.track=t.id AND p.outcome='completed'
                    AND p.duration>0 AND p.seconds>=p.duration*.8) completed
                FROM tracks t LEFT JOIN favorites f ON f.track=t.id''').fetchall()
        for row in rows:
            # One play is curiosity; a favorite or a repeat is preference.
            if row['id'] in excluded or not (row['liked'] or row['plays'] >= 2):
                continue
            strength = 1 + 3 * row['liked'] + math.log1p(row['plays']) + .25 * min(row['completed'], 4)
            video = videos.get(row['id'])
            if video:
                weights[video] = max(weights.get(video, 0), strength)
            elif row['id'] not in looked:
                lookups.append((strength, dict(row)))
        lookups.sort(key=lambda item: -item[0])
        return weights, [row for _, row in lookups]

    def resolve(self, client, row):
        """Find the YouTube Music song for a local file; ambiguous matches are recorded as unknown."""
        wanted_title, wanted_artists = title_key(row['title']), artist_keys(row['artist'])
        duration = float(row.get('duration') or 0)
        video = ''
        if wanted_title and wanted_artists:
            for item in client.search(f"{row['title']} {row['artist']}", filter='songs', limit=5)[:5]:
                if not item.get('videoId') or title_key(item.get('title')) != wanted_title:
                    continue
                # Credits are often localized ("ヰ世界情緒" / "Isekaijoucho"); an equal length confirms instead.
                names = [a.get('name', '') for a in item.get('artists') or []]
                same_length = duration > 0 and abs(float(item.get('duration_seconds') or -99) - duration) <= 8
                if artist_keys(names) & wanted_artists or same_length:
                    video = item['videoId']
                    break
        with self.a.db() as c:
            c.execute('INSERT OR REPLACE INTO similar_lookups VALUES(?,?,?)', (row['id'], video, time.time()))

    def fetch(self, client, video):
        reply = client.get_watch_playlist(videoId=video, radio=True, limit=50)
        return [{'id': t.get('videoId'), 'title': t.get('title', ''), 'artists': [a.get('name', '') for a in t.get('artists') or []],
                 'album': (t.get('album') or {}).get('name', ''), 'videoType': t.get('videoType', ''), 'length': t.get('length', '')}
                for t in reply.get('tracks', []) if t.get('videoId')][:RADIO_LENGTH]

    def refresh(self, budget=BATCH, now=None, pause=.3):
        """Fetch a few due radios per call so the background loop stays responsive."""
        if not self.lock.acquire(blocking=False):
            return 0
        try:
            now = now or time.time()
            weights, lookups = self.seeds()
            with self.a.db() as c:
                fetched = {r['video']: (r['fetched'], r['status']) for r in c.execute('SELECT video,fetched,status FROM similar_radios')}
            due = [v for v in sorted(weights, key=lambda v: -weights[v]) if v not in fetched or
                   now - fetched[v][0] > (REFRESH_SECONDS if fetched[v][1] == 'ok' else RETRY_SECONDS)]
            work = [('lookup', row) for row in lookups] + [('radio', v) for v in due]
            if not work:
                self.status = 'ready'
                return 0
            client = self.client()
            done = 0
            for kind, item in work[:budget]:
                try:
                    if kind == 'lookup':
                        self.resolve(client, item)
                    else:
                        tracks = self.fetch(client, item)
                        status = 'ok' if healthy(item, tracks) else 'unavailable'
                        with self.a.db() as c:
                            c.execute('INSERT OR REPLACE INTO similar_radios VALUES(?,?,?,?)',
                                      (item, now, status, json.dumps(tracks, ensure_ascii=False)))
                except Exception:
                    if kind == 'radio':
                        with self.a.db() as c:
                            c.execute('INSERT OR REPLACE INTO similar_radios VALUES(?,?,?,?)', (item, now, 'error', '[]'))
                    else:
                        with self.a.db() as c:
                            c.execute('INSERT OR REPLACE INTO similar_lookups VALUES(?,?,?)', (item['id'], '', now))
                done += 1
                time.sleep(pause)
            self.status = 'updating'
            return done
        finally:
            self.lock.release()

    def table(self):
        """Neighbour ranking over every cached radio; reused until a seed or radio changes."""
        weights, _ = self.seeds()
        with self.a.db() as c:
            fetched = c.execute("SELECT video,fetched FROM similar_radios WHERE status!='error' ORDER BY video").fetchall()
            stamp = (tuple(sorted(weights.items())), tuple((r['video'], r['fetched']) for r in fetched))
            if self.cached[0] == stamp:
                return self.cached[1]
            radios = {r['video']: json.loads(r['tracks']) for r in
                      c.execute("SELECT video,tracks FROM similar_radios WHERE status!='error'")}
        ranked = neighbours(radios, weights)
        self.cached = (stamp, ranked)
        return ranked

    def library_keys(self):
        keys = set()
        for song in self.a.library():
            keys |= song_keys(song['title'], song['artist'])
        return keys

    def similarity(self, songs):
        """0..1 closeness of library songs to the seeds, matched by video or by title and credit."""
        ranked = self.table()
        if not ranked:
            return {}
        top = ranked[0]['score']
        by_key = {}
        for item in ranked:
            for key in song_keys(item.get('title'), item.get('artists'), item['id']):
                by_key[key] = max(by_key.get(key, 0), item['score'])
        with self.a.db() as c:
            videos = self.videos(c)
        result = {}
        for song in songs:
            keys = song_keys(song['title'], song['artist'], videos.get(song['id'], ''))
            score = max((by_key.get(k, 0) for k in keys), default=0)
            if score:
                result[song['id']] = math.sqrt(score / top)
        return result

    def listed(self, songs):
        """Library songs that are entries of the owner's list."""
        rows = self.a.seeds.rows()
        if not rows:
            return set()
        wanted = set()
        for row in rows:
            wanted |= song_keys(row['title'], row['artists'], row['video'])
        with self.a.db() as c:
            videos = self.videos(c)
        return {s['id'] for s in songs if song_keys(s['title'], s['artist'], videos.get(s['id'], '')) & wanted}

    def list_candidates(self, rng=None):
        """List songs not yet in the library; songs whose radio proved playable go first."""
        rng = rng or random.Random()
        known = self.library_keys()
        with self.a.db() as c:
            status = {r[0]: r[1] for r in c.execute('SELECT video,status FROM similar_radios')}
        rows = [r for r in self.a.seeds.rows() if not song_keys(r['title'], r['artists']) & known]
        rng.shuffle(rows)
        rows.sort(key=lambda r: {'ok': 0, 'unavailable': 2, 'error': 2}.get(status.get(r['video']), 1))
        return [{'id': r['video'], 'title': r['title'], 'artist': r['artists'][0], 'source': 'list',
                 'album': r['album']} for r in rows]

    def radio_candidates(self, rng=None, depth=150):
        """Best neighbours not already owned; sampled by score so refills do not repeat one order."""
        rng = rng or random.Random()
        known = self.library_keys()
        for row in self.a.seeds.rows():
            known |= song_keys(row['title'], row['artists'], row['video'])
        ranked = [t for t in self.table() if not song_keys(t.get('title'), t.get('artists'), t['id']) & known][:depth]
        # Weighted order without replacement (Efraimidis–Spirakis): higher scores tend to come first.
        ranked.sort(key=lambda t: rng.random() ** (1 / t['score']), reverse=True)
        return [{'id': t['id'], 'title': t.get('title', ''), 'artist': ', '.join(t.get('artists') or []),
                 'source': 'radio', 'videoType': t.get('videoType', ''), 'similarity': t['score'],
                 'support': t['support']} for t in ranked]

    def summary(self):
        with self.a.db() as c:
            counts = {r[0]: r[1] for r in c.execute('SELECT status,COUNT(*) FROM similar_radios GROUP BY status')}
        return {'radios': counts, 'neighbours': len(self.cached[1]), 'status': self.status}
