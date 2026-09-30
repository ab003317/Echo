"""Prepared music, durable listening evidence and conservative daily rotation."""
from __future__ import annotations
import json
import math
import os
import random
import re
import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from .settings import LOCAL_TZ, TIMEZONE
from .ai import AIConfig

from .gemini import Gemini, POLICY_VERSION
from .listening_report import build_report, period_metrics

HK = LOCAL_TZ
DEFAULTS = {'enabled': os.getenv('POOL_ENABLED', 'true').lower() == 'true', 'target': 60, 'dailyPercent': 20, 'residentPlays': 3,
            'budgetBytes': 2 * 1073741824, 'aiEnabled': True}


class Radio:
    def __init__(self, app):
        self.a = app
        self.gemini = Gemini()
        self.lock = threading.Lock()
        self.wake = threading.Event()
        self.next_fill = 0

    def initialize(self):
        with self.a.db() as c:
            columns = {r['name'] for r in c.execute('PRAGMA table_info(jobs)')}
            if 'origin' not in columns:
                c.execute("ALTER TABLE jobs ADD COLUMN origin TEXT NOT NULL DEFAULT 'manual'")
            if 'visible' not in columns:
                c.execute('ALTER TABLE jobs ADD COLUMN visible INTEGER NOT NULL DEFAULT 1')
            c.executescript('''
                CREATE TABLE IF NOT EXISTS radio_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS pool_entries(video TEXT PRIMARY KEY,track TEXT NOT NULL DEFAULT '',
                    state TEXT NOT NULL,created REAL NOT NULL,retire_at REAL NOT NULL DEFAULT 0,
                    last_access REAL NOT NULL DEFAULT 0,metadata TEXT NOT NULL DEFAULT '{}');
                CREATE INDEX IF NOT EXISTS pool_track ON pool_entries(track);
                CREATE TABLE IF NOT EXISTS playback_sessions(event TEXT PRIMARY KEY,track TEXT NOT NULL,
                    seconds REAL NOT NULL,position REAL NOT NULL,duration REAL NOT NULL,started REAL NOT NULL,
                    at REAL NOT NULL,outcome TEXT NOT NULL,seq INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS playback_day ON playback_sessions(at,track);
                CREATE TABLE IF NOT EXISTS taste_reports(day TEXT PRIMARY KEY,profile TEXT NOT NULL,
                    evidence TEXT NOT NULL,model TEXT NOT NULL,created REAL NOT NULL);
            ''')

    def get(self, key, default=None):
        with self.a.db() as c:
            row = c.execute('SELECT value FROM radio_meta WHERE key=?', (key,)).fetchone()
        return json.loads(row['value']) if row else default

    def put(self, key, value):
        with self.a.db() as c:
            c.execute('INSERT INTO radio_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',
                      (key, json.dumps(value, ensure_ascii=False)))

    def config(self):
        return {**DEFAULTS, **self.get('config', {})}

    def configure(self, changes):
        value = {**self.config(), **changes}
        self.put('config', value)
        self.next_fill = 0
        self.wake.set()
        return self.status()

    def profile(self):
        profile = self.get('profile', {})
        # Unverified prose from older analysis must not survive the stricter evidence policy.
        return profile if profile.get('policyVersion') == POLICY_VERSION else {}

    def promote(self, c, track, heard=False, permanent=False):
        favorite = c.execute('SELECT liked FROM favorites WHERE track=?', (track,)).fetchone()
        plays = c.execute('SELECT COUNT(*) FROM listens WHERE track=?', (track,)).fetchone()[0]
        resident = permanent or bool(favorite and favorite[0]) or plays >= self.config()['residentPlays']
        if resident:
            c.execute("UPDATE pool_entries SET state='resident',retire_at=0 WHERE track=?", (track,))
        elif heard or plays or self.a.mixes.protected(c, track):
            c.execute("UPDATE pool_entries SET state='kept',retire_at=0 WHERE track=? AND state!='resident'", (track,))

    def event(self, body):
        now = time.time()
        with self.a.db() as c:
            c.execute('BEGIN IMMEDIATE')
            track = c.execute('SELECT * FROM tracks WHERE id=?', (body.track,)).fetchone()
            entry = c.execute('SELECT * FROM pool_entries WHERE track=?', (body.track,)).fetchone()
            if not track and not entry:
                raise self.a.HTTPException(404, '歌曲已从音乐库移除')
            if track:
                self.a.history.remember(c, track)
            previous = c.execute('SELECT * FROM playback_sessions WHERE event=?', (body.event,)).fetchone()
            if previous and previous['track'] != body.track:
                raise self.a.HTTPException(409, '聆听记录与歌曲不一致')
            if previous and previous['seq'] >= body.seq:
                return {'ok': True}
            if previous and previous['outcome'] != 'progress' and body.outcome == 'progress':
                return {'ok': True}
            duration = track['duration'] if track else json.loads(entry['metadata']).get('duration', body.duration)
            seconds = max(body.seconds, previous['seconds'] if previous else 0)
            c.execute('''INSERT INTO playback_sessions VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(event) DO UPDATE SET
                seconds=excluded.seconds,position=excluded.position,at=excluded.at,outcome=excluded.outcome,seq=excluded.seq''',
                (body.event, body.track, seconds, body.position, duration, min(body.started, now),
                 min(body.at, now), body.outcome, body.seq))
            threshold = min(30, duration / 2) if duration > 0 else 30
            if seconds >= threshold:
                c.execute('''INSERT INTO listens VALUES(?,?,?,?) ON CONFLICT(event) DO UPDATE SET
                    seconds=MAX(listens.seconds,excluded.seconds),at=MAX(listens.at,excluded.at)''',
                    (body.event, body.track, seconds, min(body.at, now)))
            if seconds > 0:
                self.promote(c, body.track, heard=True)
                # A long-offline phone can rescue a rotated song using its stable video identity.
                if not track and entry:
                    c.execute("UPDATE jobs SET status='queued',error='' WHERE video=? AND status NOT IN ('queued','downloading')", (entry['video'],))
        return {'ok': True}

    def access(self, track):
        with self.a.db() as c:
            c.execute('UPDATE pool_entries SET last_access=? WHERE track=?', (time.time(), track))

    def keep(self, track):
        with self.a.db() as c:
            self.promote(c, track, permanent=True)
        return {'ok': True}

    def completed(self, c, job, track):
        if job.get('origin') == 'pool':
            c.execute("UPDATE pool_entries SET track=?,state=CASE WHEN state IN ('kept','resident') THEN state ELSE 'explore' END WHERE video=?", (track, job['video']))

    def used(self):
        with self.a.db() as c:
            return c.execute('SELECT COALESCE(SUM(t.size),0) FROM pool_entries p JOIN tracks t ON t.id=p.track').fetchone()[0]

    def status(self):
        with self.a.db() as c:
            counts = {r[0]: r[1] for r in c.execute('''SELECT p.state,COUNT(*) FROM pool_entries p JOIN tracks t ON t.id=p.track
                LEFT JOIN content_checks q ON q.video=p.video WHERE COALESCE(q.status,'approved')='approved' GROUP BY p.state''')}
            pending = c.execute("SELECT COUNT(*) FROM jobs WHERE origin='pool' AND status IN ('queued','downloading')").fetchone()[0]
            report = c.execute('SELECT profile,evidence FROM taste_reports WHERE day=?', (self.get('analysisDay', ''),)).fetchone()
        profile = self.profile()
        # Keep every displayed metric tied to the evidence used for this analysis.
        detail = None
        if report and profile:
            saved_profile, evidence = json.loads(report['profile']), json.loads(report['evidence'])
            if saved_profile.get('policyVersion') == POLICY_VERSION:
                detail = build_report(evidence, saved_profile)
        return {'config': self.config(), 'counts': counts, 'pending': pending, 'bytes': self.used(),
                'status': self.get('status', '正在准备新歌'), 'lastRotation': self.get('rotation', ''),
                'analysis': {'day': self.get('analysisDay', ''), 'at': self.get('analysisAt', 0),
                             'summary': profile.get('summary', ''), 'focus': profile.get('focus', []),
                             'model': self.get('model', ''), 'report': detail, 'status': self.get('aiStatus', '等待分析')}}

    def evidence(self, day):
        start = datetime.fromisoformat(day).replace(tzinfo=HK).timestamp()
        end = (datetime.fromisoformat(day).replace(tzinfo=HK) + timedelta(days=1)).timestamp()
        with self.a.db() as c:
            def stats(since, until):
                return [dict(r) for r in c.execute('''SELECT t.id track,t.title,t.artist,COUNT(*) sessions,
                    ROUND(SUM(p.seconds)) seconds,
                    SUM(CASE WHEN p.seconds>=MIN(30,CASE WHEN p.duration>0 THEN p.duration/2 ELSE 30 END) THEN 1 ELSE 0 END) effectivePlays,
                    SUM(CASE WHEN p.outcome='completed' AND p.duration>0 AND p.seconds>=p.duration*.8 THEN 1 ELSE 0 END) completed,
                    SUM(CASE WHEN p.outcome='skipped' AND p.seconds<30 AND (p.duration<=0 OR p.seconds<p.duration*.25) THEN 1 ELSE 0 END) earlySkips,
                    SUM(CASE WHEN p.outcome='skipped' THEN 1 ELSE 0 END) skipped,
                    ROUND(AVG(MIN(1,CASE WHEN p.duration>0 THEN p.seconds/p.duration ELSE 0 END)),3) meanFraction
                    FROM playback_sessions p JOIN tracks t ON t.id=p.track WHERE p.at>=? AND p.at<?
                    GROUP BY p.track ORDER BY SUM(p.seconds) DESC''', (since, until))]
            today = stats(start, end)
            recent = stats(end - 30 * 86400, end)
            legacy = [dict(r) for r in c.execute('''SELECT t.id track,t.title,t.artist,COUNT(*) legacyPlays FROM listens l
                JOIN tracks t ON t.id=l.track WHERE l.at>=? AND l.at<? AND NOT EXISTS
                (SELECT 1 FROM playback_sessions p WHERE p.event=l.event)
                GROUP BY l.track ORDER BY COUNT(*) DESC LIMIT 40''', (end - 90 * 86400, end))]
            favorites = [dict(r) for r in c.execute('SELECT t.id track,t.title,t.artist FROM favorites f JOIN tracks t ON t.id=f.track WHERE f.liked=1 LIMIT 60')]
            artists = [r[0] for r in c.execute('SELECT artist FROM tracks GROUP BY artist ORDER BY COUNT(*) DESC LIMIT 40')]
        excluded = self.a.quality.excluded()
        from collections import Counter
        artists = [name for name, _ in Counter(s['artist'] for s in self.a.library() if s['id'] not in excluded).most_common(40)]
        today = [r for r in today if r['track'] not in excluded]
        recent = [r for r in recent if r['track'] not in excluded]
        return {'day': day, 'timezone': TIMEZONE,
                'totals': {'today': period_metrics(today, complete=True), 'recent': period_metrics(recent, complete=True)},
                **{period: [r for r in rows if r['track'] not in excluded] for period, rows in
                   [('today', today[:70]), ('recent', recent[:70]), ('legacy', legacy), ('favorites', favorites)]}, 'libraryArtists': artists,
                'external':self.a.seeds.evidence(), 'seedArtists':self.a.seeds.affinities(),
                'audioSamples':self.a.seeds.audio_evidence()}

    def analyze(self, day):
        self.put('aiStatus', '正在分析聆听偏好')
        evidence = self.evidence(day)
        profile, model = self.gemini.analyze(evidence)
        with self.a.db() as c:
            c.execute('INSERT INTO taste_reports VALUES(?,?,?,?,?) ON CONFLICT(day) DO UPDATE SET profile=excluded.profile,evidence=excluded.evidence,model=excluded.model,created=excluded.created',
                      (day, json.dumps(profile, ensure_ascii=False), json.dumps(evidence, ensure_ascii=False), model, time.time()))
        self.put('profile', profile)
        self.put('analysisDay', day)
        self.put('analysisAt', time.time())
        self.put('model', model)
        self.put('aiStatus', '已更新聆听偏好')
        return profile

    def rotate(self, day, now=None):
        now = now or time.time()
        if self.get('rotation') == day:
            return
        config = self.config()
        with self.a.db() as c:
            c.execute('BEGIN IMMEDIATE')
            # Reconcile old-client listens and favorites before selecting rotation candidates.
            for row in c.execute("SELECT track FROM pool_entries WHERE track!='' AND state!='resident'").fetchall():
                heard = bool(c.execute('SELECT 1 FROM playback_sessions WHERE track=? AND seconds>0 LIMIT 1', (row[0],)).fetchone())
                self.promote(c, row[0], heard=heard)
            eligible = [r[0] for r in c.execute("SELECT video FROM pool_entries WHERE state='explore' AND created<? AND last_access<?", (now - 86400, now - 6 * 3600))]
            random.Random(day).shuffle(eligible)
            count = min(len(eligible), math.ceil(config['target'] * config['dailyPercent'] / 100))
            for video in eligible[:count]:
                c.execute("UPDATE pool_entries SET state='retiring',retire_at=? WHERE video=?", (now + 72 * 3600, video))
        self.put('rotation', day)

    def cleanup(self, now=None):
        now = now or time.time()
        owned = (self.a.ROOT / 'Serein Discoveries').resolve()
        with self.a.scan_lock:
            with self.a.db() as c:
                c.execute('BEGIN IMMEDIATE')
                rows = c.execute("SELECT p.*,t.path FROM pool_entries p JOIN tracks t ON t.id=p.track WHERE p.state='retiring' AND p.retire_at<? AND p.last_access<?", (now, now - 6 * 3600)).fetchall()
                for row in rows:
                    heard = bool(c.execute('SELECT 1 FROM playback_sessions WHERE track=? AND seconds>0 LIMIT 1', (row['track'],)).fetchone())
                    self.promote(c, row['track'], heard=heard)
                    state = c.execute('SELECT state FROM pool_entries WHERE video=?', (row['video'],)).fetchone()[0]
                    if state != 'retiring':
                        continue
                    path = self.a.ROOT / row['path']
                    origin = c.execute('SELECT origin FROM jobs WHERE video=?', (row['video'],)).fetchone()
                    if not origin or origin[0] != 'pool' or path.is_symlink() or path.resolve().parent != owned or path.name != row['video'] + '.m4a':
                        continue
                    path.unlink(missing_ok=True)
                    cover = self.a.DATA / 'covers' / row['track']
                    if re.fullmatch('[a-f0-9]{24}', row['track']):
                        cover.unlink(missing_ok=True)
                    c.execute('DELETE FROM tracks WHERE id=?', (row['track'],))
                    c.execute("UPDATE pool_entries SET state='retired' WHERE video=?", (row['video'],))

    def queue_candidates(self, candidates, count):
        queued = 0
        with self.a.db() as c:
            c.execute('BEGIN IMMEDIATE')
            for song in candidates:
                if queued >= count:
                    break
                video = song.get('id', '')
                if not re.fullmatch(r'[A-Za-z0-9_-]{11}', video):
                    continue
                if c.execute('SELECT 1 FROM jobs WHERE video=?', (video,)).fetchone():
                    continue
                jid = str(uuid.uuid4())
                c.execute("INSERT INTO jobs(id,video,title,status,created,origin,visible) VALUES(?,?,?,'queued',?,'pool',0)", (jid, video, song['title'], time.time()))
                c.execute("INSERT INTO pool_entries(video,state,created,metadata) VALUES(?,'pending',?,?)", (video, time.time(), json.dumps(song, ensure_ascii=False)))
                queued += 1
        return queued

    def fill(self):
        config = self.config()
        with self.a.db() as c:
            active = c.execute("""SELECT COUNT(*) FROM pool_entries p JOIN tracks t ON t.id=p.track LEFT JOIN content_checks q ON q.video=p.video
                WHERE p.state='explore' AND COALESCE(q.status,'approved')='approved'""").fetchone()[0]
            pending = c.execute("SELECT COUNT(*) FROM jobs WHERE origin='pool' AND status IN ('queued','downloading')").fetchone()[0]
        missing = config['target'] - active - pending
        if missing <= 0:
            self.put('status', '新歌池已准备好' if not pending else '正在准备新歌')
            return
        if self.used() + (pending + 1) * 12_000_000 > config['budgetBytes'] or shutil.disk_usage(self.a.ROOT).free < 1073741824:
            self.put('status', '已达到新歌池容量，保留歌曲不自动删除')
            return
        if pending >= 8:
            return
        rng = random.Random()
        personal = list(self.profile().get('queries', []))
        explore = self.a.discovery_queries(str(uuid.uuid4()), 'new', use_ai=False)
        rng.shuffle(personal)
        rng.shuffle(explore)
        # Two personalized searches and one exploratory source on every fill.
        queries = (personal[:2] + explore[:1]) if personal else explore[:3]
        seed_artists = self.a.seeds.affinities()
        if seed_artists:
            pick = rng.choices(seed_artists, weights=[a['weight'] for a in seed_artists], k=1)[0]
            queries[0] = pick['artist'] + ' official audio'
        # Let verified complete collections participate, rather than searching only for singles.
        artists = [a['artist'] for a in self.profile().get('artists', []) if a['weight'] > 0]
        if artists and rng.random() < .25:
            queries[-1] = rng.choice(artists) + ' full album official'
        explore_suffix = ' new release official audio'
        explore_artists = {q[:-len(explore_suffix)] for q in explore if q.endswith(explore_suffix)}
        known_artists = sorted({a['artist'] for a in seed_artists} | set(artists) | explore_artists, key=len, reverse=True)
        queued = 0
        cursor = self.get('sourceCursor', 0)
        allowance = min(missing, 8 - pending)
        if allowance < len(queries):
            queries = rng.sample(queries, allowance)
        searches = []
        for query in queries:
            expected = next((artist for artist in known_artists if query.casefold() in
                             (artist.casefold(),artist.casefold()+' official audio',artist.casefold()+' full album official',
                              artist.casefold()+explore_suffix)), '')
            search_query = '"'+expected+'" official audio' if expected and query.casefold()==expected.casefold() else query
            searches.append((search_query,expected))
        with ThreadPoolExecutor(max_workers=3) as workers:
            futures = [workers.submit(self.a.search_youtube,query,page=cursor % 4,size=18) for query,_ in searches]
            for index, ((query,expected),future) in enumerate(zip(searches,futures)):
                try:
                    rows, _ = future.result()
                except Exception:
                    continue
                rows = [{**row,'discoveryQuery':query,'expectedArtist':expected} for row in rows]
                rng.shuffle(rows)
                rows.sort(key=lambda s: (str(s.get('artist','')).endswith(' - Topic'),
                    bool(re.search(r'official audio|original song|\bMV\b', s.get('title',''), re.I))), reverse=True)
                share = math.ceil((allowance - queued) / (len(queries) - index))
                queued += self.queue_candidates(rows, share)
                if queued >= min(missing, 8 - pending):
                    break
        self.put('sourceCursor', cursor + 1)
        self.put('status', '正在准备新歌' if queued else '继续寻找未入库的新歌')

    def ready(self, seed, page, mode, excluded):
        songs = self.a.recommendations(limit=10000, seed=seed, mode=mode)
        # Never advertise a file before the atomic download and index commit completes.
        songs = [s for s in songs if s['id'] not in excluded and s.get('pool') not in ('retiring', 'retired')]
        # For ready streams the client's accumulated IDs form the cursor, so new arrivals can append.
        chosen = []
        for song in songs:
            try:
                self.a.find_track(song['id'])
            except self.a.HTTPException:
                continue
            chosen.append(song)
            if len(chosen) == 18:
                break
        return {'tracks': [{'id': s['id'], 'track': s['id'], 'title': s['title'], 'artist': s['artist'],
                            'duration': s['duration'], 'cover': '', 'song': s} for s in chosen],
                'seed': seed, 'page': page, 'nextPage': page + 1, 'hasMore': True,
                'retryAfter': 30 if len(chosen) < 18 else 0, 'readyOnly': True, 'query': '', 'mode': mode,
                'excluded': list(self.a.quality.excluded())}

    def tick(self):
        if not self.lock.acquire(blocking=False):
            return
        try:
            config = self.config()
            local = datetime.now(HK)
            day = (local.date() - timedelta(days=1)).isoformat()
            due = local.hour >= 4 and self.get('analysisDay', '') < day
            if AIConfig.from_env().enabled and config['aiEnabled'] and (not self.profile() or due) and time.time() >= self.get('aiRetryAt', 0):
                try:
                    self.analyze(day)
                except Exception as e:
                    self.put('aiStatus', str(e) if isinstance(e, RuntimeError) else '偏好分析暂时不可用，将沿用已有偏好')
                    self.put('aiRetryAt', time.time() + 3600)
            if not config['enabled']:
                self.put('status', '自动准备已暂停，已有歌曲仍可播放')
                return
            if local.hour >= 4:
                self.rotate(local.date().isoformat())
            self.cleanup()
            if time.time() >= self.next_fill:
                self.next_fill = time.time() + 45
                try:
                    self.fill()
                except Exception:
                    self.next_fill = time.time() + 180
                    self.put('status', '新歌来源暂时不可用，已准备的歌曲仍可播放')
        finally:
            self.lock.release()

    def loop(self, stop):
        while not stop.is_set():
            try:
                self.tick()
            except Exception:
                self.put('status', '新歌准备稍后重试')
            self.wake.wait(15)
            self.wake.clear()
