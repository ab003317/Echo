"""Owner-provided preference directions, separate from favorites and listening history."""
from __future__ import annotations
from collections import Counter, defaultdict
import csv
import hashlib
import io
import json
import math
import re
import time
import unicodedata


def norm(value):
    return unicodedata.normalize('NFKC', str(value or '')).strip()


def artist_matches(expected, info):
    """Match explicit credits, never substrings such as KAF in 'Kaf Inah'."""
    def normalized(value):
        value = norm(value).casefold()
        value = re.sub(r'\b(?:official|youtube|channel|vevo|topic)\b|公式', '', value)
        return ''.join(ch for ch in value if ch.isalnum())
    wanted = normalized(expected)
    labels = [str(info.get('artist') or '')]
    if info.get('channel_is_verified'):
        labels.append(str(info.get('channel') or ''))
    for label in labels:
        parts = [label] + re.split(r'\s*/\s*|\s+-|\s+feat\.?\s+|\s*&\s*|[\[\]()【】]', label, flags=re.I)
        if wanted and any(normalized(part) == wanted for part in parts if part.strip()):
            return True
    return False


def parse_export(raw):
    text = raw.decode('utf-8-sig') if isinstance(raw, bytes) else raw.lstrip('\ufeff')
    reader = csv.DictReader(io.StringIO(text, newline=''))
    headers = reader.fieldnames or []
    id_column = next((h for h in headers if h.strip().casefold() in ('影片 id','视频 id','video id')), None)
    artist_columns = [h for h in headers if re.fullmatch(r'Artist Name \d+', h)]
    if not id_column or not {'Song Title','Album Title'} <= set(headers) or not artist_columns:
        raise ValueError('Unsupported YouTube Music export columns')
    result, seen, duplicate = [], set(), 0
    for number, row in enumerate(reader, 2):
        video = norm(row.get(id_column))
        if not re.fullmatch(r'[A-Za-z0-9_-]{11}', video):
            raise ValueError(f'Invalid video ID on CSV line {number}')
        artists = list(dict.fromkeys(norm(row.get(k)) for k in artist_columns if norm(row.get(k))))
        title = norm(row.get('Song Title'))
        if not title or not artists:
            raise ValueError(f'Missing title or artist on CSV line {number}')
        if video in seen:
            duplicate += 1
            continue
        seen.add(video)
        result.append({'video':video,'title':title,'album':norm(row.get('Album Title')),'artists':artists})
    if not result:
        raise ValueError('Export contains no tracks')
    return result, duplicate


def album_group(row):
    # The export's primary credit distinguishes unrelated albums with the same name.
    return (row['artists'][0].casefold(), row['album'].casefold() or row['video'])


def weights(rows):
    albums = Counter(album_group(r) for r in rows)
    scores = defaultdict(float)
    display = {}
    for row in rows:
        contribution = 1/math.sqrt(albums[album_group(row)])/len(row['artists'])
        for artist in row['artists']:
            key = artist.casefold()
            display.setdefault(key, artist)
            scores[key] += contribution
    maximum = max(scores.values(), default=1)
    return [{'artist':display[key], 'weight':round(.15+.3*math.sqrt(value/maximum),4),
             'albumBalancedCount':round(value,4)} for key,value in sorted(scores.items(),key=lambda item:-item[1])]


class Seeds:
    def __init__(self, app):
        self.a = app

    def initialize(self):
        with self.a.db() as c:
            c.executescript('''
                CREATE TABLE IF NOT EXISTS preference_seed_imports(source TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,
                    filename TEXT NOT NULL,signal TEXT NOT NULL,created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS preference_seeds(source TEXT NOT NULL,video TEXT NOT NULL,title TEXT NOT NULL,
                    album TEXT NOT NULL,artists TEXT NOT NULL,PRIMARY KEY(source,video));
                CREATE TABLE IF NOT EXISTS preference_seed_audio(video TEXT PRIMARY KEY,record TEXT NOT NULL,created REAL NOT NULL);
            ''')

    def import_csv(self, raw, filename, source='youtube-music', signal='direction'):
        if signal != 'direction':
            raise ValueError('This import is preference direction, never implicit favorites or play history')
        rows, duplicates = parse_export(raw)
        fingerprint = hashlib.sha256(raw if isinstance(raw,bytes) else raw.encode()).hexdigest()
        with self.a.db() as c:
            c.execute('INSERT INTO preference_seed_imports VALUES(?,?,?,?,?) ON CONFLICT(source) DO UPDATE SET '
                      'fingerprint=excluded.fingerprint,filename=excluded.filename,signal=excluded.signal,created=excluded.created',
                      (source,fingerprint,filename,signal,time.time()))
            c.execute('DELETE FROM preference_seeds WHERE source=?',(source,))
            c.executemany('INSERT INTO preference_seeds VALUES(?,?,?,?,?)',
                          [(source,r['video'],r['title'],r['album'],json.dumps(r['artists'],ensure_ascii=False)) for r in rows])
        return {'tracks':len(rows),'duplicatesRemoved':duplicates,'signal':signal,'sha256':fingerprint}

    def rows(self):
        with self.a.db() as c:
            rows = [dict(r) for r in c.execute('SELECT * FROM preference_seeds ORDER BY source,video')]
        result, seen = [], set()
        for row in rows:
            if row['video'] not in seen:
                result.append({**row,'artists':json.loads(row['artists'])})
                seen.add(row['video'])
        return result

    def affinities(self):
        return weights(self.rows())

    def evidence(self):
        rows = self.rows()
        albums = Counter(album_group(r) for r in rows)
        return [{'track':'yt:'+r['video'],'title':r['title'],'artists':r['artists'],'artist':r['artists'][0],
                 'signal':'direction','source':'youtube-music','albumGroup':hashlib.sha256(repr(album_group(r)).encode()).hexdigest()[:12],
                 'albumWeight':round(1/math.sqrt(albums[album_group(r)]),4)} for r in rows]

    def import_audio(self, records):
        videos = {r['video'] for r in self.rows()}
        allowed = {'vocals','instrumental','distorted_guitar','acoustic_guitar','piano','strings','synth','drum_kit',
                   'electronic_drums','bass','steady_pulse','syncopation','busy_rhythm','sparse_arrangement',
                   'dense_arrangement','strong_section_contrast','choir','spoken_voice','slow_pulse','moderate_pulse','fast_pulse'}
        checked = []
        for record in records:
            if record.get('video') not in videos or record.get('music') is not True:
                continue
            seconds = float(record.get('sampledSeconds',0))
            segments = record.get('segments',[])
            if not math.isfinite(seconds) or not 0 < seconds <= 65 or not 1 <= len(segments) <= 3:
                raise ValueError('Invalid audio sample coverage')
            if not re.fullmatch(r'[a-f0-9]{64}',record.get('inputAudioSha256','')):
                raise ValueError('Missing audio input fingerprint')
            traits, seen = [], set()
            for trait in record.get('traits',[]):
                tag = trait.get('tag')
                confidence, timestamp = float(trait.get('confidence',0)),float(trait.get('atSecond',-1))
                if tag in allowed and tag not in seen and .8 <= confidence <= 1 and 0 <= timestamp <= seconds+len(segments):
                    traits.append({'tag':tag,'confidence':confidence,'atSecond':timestamp})
                    seen.add(tag)
            if {'vocals','instrumental'} <= seen:
                raise ValueError('Contradictory audio evidence')
            checked.append({**record,'traits':traits})
        with self.a.db() as c:
            c.executemany('INSERT INTO preference_seed_audio VALUES(?,?,?) ON CONFLICT(video) DO UPDATE SET '
                          'record=excluded.record,created=excluded.created',
                          [(r['video'],json.dumps(r,ensure_ascii=False),time.time()) for r in checked])
        return len(checked)

    def audio_evidence(self):
        with self.a.db() as c:
            rows = [json.loads(r[0]) for r in c.execute('SELECT DISTINCT a.record FROM preference_seed_audio a '
                    'JOIN preference_seeds s ON s.video=a.video')]
        return [{'track':'yt:'+r['video'],'sampledSeconds':r['sampledSeconds'],'segments':r['segments'],
                 'traits':r['traits'],'model':r['model'],'source':'model_inference_from_audio_excerpts'} for r in rows]
