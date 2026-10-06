"""Single-owner music library. Original music is read-only; imports are additive."""
from __future__ import annotations

import contextlib
import hashlib
import json
import math
import os
import random
import re
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Literal
from urllib.parse import parse_qs, urlparse

import mutagen
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi import Path as ApiPath
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from .ai import AIConfig
from pydantic import BaseModel, Field
from .radio import Radio, EARLY_SKIP
from .content import ContentGuard
from .seeds import Seeds, artist_matches
from .similar import Similar, artist_keys
from .history import History
from .mixes import Mixes
from .traits import AudioTraits
from .charts import Charts, REGIONS

ROOT = Path(os.getenv("SEREIN_MUSIC_ROOT", "music")).resolve()
DATA = Path(os.getenv("SEREIN_DATA_DIR", "data")).resolve()
DATA.mkdir(parents=True, exist_ok=True)
(DATA / "covers").mkdir(exist_ok=True)
DB = DATA / "serein.sqlite3"
scan_lock = threading.Lock()
publish_lock = threading.Lock()
search_lock = threading.Lock()
search_cache: dict[str, tuple[float, list]] = {}
search_slots = threading.Semaphore(3)
discovery_sessions = {}
discovery_lock = threading.Lock()
EXTENSIONS = {".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac"}


@contextlib.contextmanager
def db():
    con = sqlite3.connect(DB, timeout=30)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    try:
        yield con
        con.commit()
    finally:
        con.close()


def initialize():
    with db() as c:
        c.executescript('''
        CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,path TEXT UNIQUE,title TEXT,artist TEXT,
          album TEXT,duration REAL,size INTEGER,mtime REAL,added REAL,genre TEXT,cover INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS favorites(track TEXT PRIMARY KEY,liked INTEGER,updated REAL);
        CREATE TABLE IF NOT EXISTS listens(event TEXT PRIMARY KEY,track TEXT,seconds REAL,at REAL);
        CREATE INDEX IF NOT EXISTS listens_track ON listens(track,at);
        CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,video TEXT UNIQUE,title TEXT,status TEXT,
          progress REAL DEFAULT 0,error TEXT DEFAULT '',created REAL,track TEXT DEFAULT '');
        ''')
    radio.initialize()
    quality.initialize()
    seeds.initialize()
    similar.initialize()
    mixes.initialize()
    history.initialize()
    charts.initialize()


def scan(wait=False):
    if not scan_lock.acquire(blocking=wait):
        return
    try:
        if not ROOT.is_dir():
            raise RuntimeError("音乐目录不可用")
        seen = set()
        with db() as c:
            for path in ROOT.rglob("*"):
                if path.suffix.lower() not in EXTENSIONS or not path.is_file():
                    continue
                if not path.resolve().is_relative_to(ROOT) or ".staging" in path.parts:
                    continue
                relative = path.relative_to(ROOT).as_posix()
                tid = hashlib.sha256(relative.encode()).hexdigest()[:24]
                seen.add(tid)
                stat = path.stat()
                old = c.execute("SELECT mtime,size FROM tracks WHERE id=?", (tid,)).fetchone()
                if old and old["mtime"] == stat.st_mtime and old["size"] == stat.st_size:
                    continue
                name = path.stem.replace("——", "_").split("_")
                title, artist = name[-1].strip(), " / ".join(name[:-1]) or "未知歌手"
                album, genre, duration, cover = "", "", 0.0, 0
                try:
                    audio = mutagen.File(path, easy=True)
                    # Mutagen containers without tags are falsey, but still have valid duration.
                    if audio is not None:
                        title = str(audio.get("title", [title])[0])
                        artist = " / ".join(audio.get("artist", [artist]))
                        album = str(audio.get("album", [""])[0])
                        genre = str(audio.get("genre", [""])[0])
                        duration = float(audio.info.length)
                    raw = mutagen.File(path)
                    pictures = getattr(raw, "pictures", [])
                    tags = getattr(raw, "tags", None)
                    image = pictures[0].data if pictures else None
                    if not image and tags:
                        apic = tags.getall("APIC") if hasattr(tags, "getall") else []
                        image = apic[0].data if apic else (tags.get("covr", [None])[0])
                    if not image:
                        for cover_name in ["cover.jpg", "folder.jpg", "cover.png", path.stem + ".jpg"]:
                            sidecar = path.parent / cover_name
                            if sidecar.is_file() and sidecar.resolve().is_relative_to(ROOT):
                                image = sidecar.read_bytes()
                                break
                    if image and len(image) < 15_000_000:
                        (DATA / "covers" / tid).write_bytes(image)
                        cover = 1
                except Exception:
                    pass  # An untagged file remains playable and searchable by filename.
                c.execute('''INSERT INTO tracks VALUES(?,?,?,?,?,?,?,?,?,?,?)
                  ON CONFLICT(id) DO UPDATE SET title=excluded.title,artist=excluded.artist,
                  album=excluded.album,duration=excluded.duration,size=excluded.size,
                  mtime=excluded.mtime,genre=excluded.genre,cover=excluded.cover''',
                  (tid, relative, title, artist, album, duration, stat.st_size, stat.st_mtime,
                   stat.st_mtime, genre, cover))
            for row in c.execute("SELECT id FROM tracks").fetchall():
                if row[0] not in seen:
                    c.execute("DELETE FROM tracks WHERE id=?", (row[0],))
    finally:
        scan_lock.release()


def library():
    with db() as c:
        rows = c.execute('''SELECT t.*,COALESCE(f.liked,0) AS favorite,COALESCE(p.state,'') AS pool,
          COALESCE(l.plays,0) AS plays,COALESCE(l.last_played,0) AS lastPlayed
          FROM tracks t LEFT JOIN favorites f ON f.track=t.id LEFT JOIN pool_entries p ON p.track=t.id
          LEFT JOIN (SELECT track,COUNT(*) AS plays,MAX(at) AS last_played FROM listens GROUP BY track) l
          ON l.track=t.id ORDER BY t.added DESC''').fetchall()
    excluded = quality.excluded(library=True)
    return [{k: row[k] for k in row.keys() if k != "path"} for row in rows if row['id'] not in excluded]


def recommendations(limit=40, seed="", mode="taste"):
    excluded = quality.excluded()
    songs = [s for s in library() if s['id'] not in excluded and s.get('pool') not in ('retiring', 'retired')]
    # Credits are compared without channel suffixes, so "Reol Official" still counts as Reol.
    credits = {s['id']: artist_keys(s['artist']) for s in songs}

    def keyed(items):
        table = {}
        for item in items:
            for key in artist_keys(item['artist']):
                if abs(item['weight']) > abs(table.get(key, 0)):
                    table[key] = item['weight']
        return table

    def strongest(table, keys):
        present = [table[k] for k in keys if k in table]
        return max(present, key=abs) if present else 0

    affinity: dict[str, float] = {}
    for s in songs:
        for key in credits[s['id']]:
            affinity[key] = affinity.get(key, 0) + math.log1p(s["plays"]) + 3 * s["favorite"]
    profile = radio.profile()
    ai_weights = keyed(profile.get('artists', []))
    seed_weights = keyed(seeds.affinities())
    skips: dict[str, int] = {}
    with db() as c:
        disliked = radio.disliked(c)
        song_skips = {r[0]: r[1] for r in c.execute(
            "SELECT track,COUNT(*) FROM playback_sessions WHERE " + EARLY_SKIP + " GROUP BY track")}
        for artist, count in c.execute('''SELECT t.artist,COUNT(*) FROM playback_sessions p
            JOIN tracks t ON t.id=p.track WHERE p.outcome='skipped' AND p.seconds<30 AND
            (p.duration<=0 OR p.seconds<p.duration*.25) AND p.at>? AND NOT EXISTS
            (SELECT 1 FROM content_checks q JOIN pool_entries pe ON pe.video=q.video
             WHERE pe.track=p.track AND q.status IN ('rejected','review')) GROUP BY t.artist''', (time.time()-14*86400,)):
            for key in artist_keys(artist):
                skips[key] = skips.get(key, 0) + count
    # Songs the owner keeps skipping leave recommendations entirely; a single skip only lowers one song.
    songs = [s for s in songs if s['id'] not in disliked]
    try:
        closeness, listed = similar.similarity(songs), similar.listed(songs)
    except Exception:
        closeness, listed = {}, set()
    rng = random.Random(seed or time.strftime("%Y-%m-%d"))
    for s in songs:
        keys = credits[s['id']]
        novelty = 1 / (1 + s["plays"])
        recent_penalty = 4 if time.time() - s["lastPlayed"] < 6 * 3600 else 0
        fresh = 2 if s.get('pool') == 'explore' else 0
        if mode == 'new': fresh *= 2
        near = closeness.get(s['id'], 0)
        s["score"] = strongest(affinity, keys) * .5 + novelty * 3 + rng.random() * 1.5 - recent_penalty + fresh
        s['score'] += strongest(ai_weights, keys) * 2 - min(3, math.log1p(strongest(skips, keys)))
        # Owner-confirmed broad direction is weaker than explicit favorites and repeat plays.
        s['score'] += strongest(seed_weights, keys) * 1.5
        # Song-level evidence: neighbours of liked songs, and the owner's own list entries.
        s['score'] += 4 * near + (2.5 if s['id'] in listed else 0) - min(4, 1.5 * song_skips.get(s['id'], 0))
        s["reason"] = ("来自你的口味清单" if s['id'] in listed else "与你喜欢的歌相似" if near >= .3 else
                       "来自你常听的歌手" if strongest(affinity, keys) > 0 else "探索音乐库")
    songs.sort(key=lambda s: s["score"], reverse=True)
    # Interleave artists so one heavily played artist cannot fill the first page.
    selected, selected_ids, counts = [], set(), {}
    for cap in (2, 10000):
        for s in songs:
            if s['id'] in selected_ids or counts.get(s["artist"], 0) >= cap:
                continue
            selected.append(s)
            selected_ids.add(s['id'])
            counts[s["artist"]] = counts.get(s["artist"], 0) + 1
            if len(selected) >= limit:
                return selected
    return selected


def video_id(value: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", value):
        return value
    u = urlparse(value)
    if u.scheme != "https" or u.username or u.password or u.port not in (None, 443):
        raise ValueError("请使用 YouTube 单曲 HTTPS 链接")
    if u.hostname == "youtu.be":
        candidate = u.path.strip("/")
    elif u.hostname in {"youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com"}:
        candidate = parse_qs(u.query).get("v", [""])[0]
        if not candidate and re.match(r"^/(shorts|embed)/", u.path):
            candidate = u.path.split("/")[2]
    else:
        raise ValueError("目前支持 YouTube 和 YouTube Music")
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate):
        raise ValueError("请提供单曲链接，不是播放列表")
    return candidate


def yt_command():
    command = [sys.executable, "-m", "yt_dlp", "--ignore-config", "--no-playlist",
               "--socket-timeout", "20", "--retries", "2", "--js-runtimes", "node"]
    if os.getenv('YOUTUBE_PROXY_URL'):
        command += ['--proxy', os.environ['YOUTUBE_PROXY_URL']]
    if os.getenv('YOUTUBE_COOKIES_FILE'):
        command += ['--cookies', os.environ['YOUTUBE_COOKIES_FILE']]
    return command


def youtube_info(video):
    result = subprocess.run(yt_command() + ['--skip-download', '--dump-single-json', '--',
        'https://www.youtube.com/watch?v=' + video], capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError('音源资料暂时无法核实')
    info = json.loads(result.stdout)
    if info.get('id') != video:
        raise RuntimeError('音源身份不一致')
    return info


def search_youtube(query: str, page=0, size=18):
    cache_key = (query, page, size)
    with search_lock:
        cached = search_cache.get(cache_key)
        if cached and time.time() - cached[0] < 1800:
            return cached[1]
    start, end = page * size + 1, (page + 1) * size
    with search_slots:
        result = subprocess.run(yt_command() + ["--flat-playlist", "--dump-single-json",
                                "--playlist-start", str(start), "--playlist-end", str(end), "--", f"ytsearch{end}:" + query],
                                capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise HTTPException(502, "YouTube 暂时无法连接，请稍后重试")
    entries = json.loads(result.stdout).get("entries", [])
    songs = [{"id": e["id"], "title": e.get("title", ""), "artist": e.get("channel") or e.get("uploader") or "YouTube",
              "duration": e.get("duration") or 0, "cover": f'https://i.ytimg.com/vi/{e["id"]}/hqdefault.jpg'}
             for e in entries if e and re.fullmatch(r"[A-Za-z0-9_-]{11}", e.get("id", ""))
             and not e.get("is_live")]
    value = (songs, len(entries) >= size)
    with search_lock:
        search_cache[cache_key] = (time.time(), value)
        if len(search_cache) > 300:
            search_cache.pop(next(iter(search_cache)))
    return value


def discovery_queries(seed, mode, expansion=0, use_ai=True):
    rng = random.Random(seed)
    excluded = quality.excluded()
    # Automatic guesses must not turn into preference evidence merely by being downloaded.
    songs = [s for s in library() if s['id'] not in excluded and
             (not s.get('pool') or s.get('pool') in ('kept','resident') or s['plays'] or s['favorite'])]
    weights = {}
    for song in songs:
        artist = song["artist"].split(" / ")[0].strip()
        if artist and artist != "未知歌手":
            weights[artist] = weights.get(artist, 1) + math.log1p(song["plays"]) + song["favorite"] * 3
    # Weighted shuffle retains exploration, instead of choosing the same top artist forever.
    artists = sorted(weights, key=lambda a: rng.random() ** (1 / weights[a]), reverse=True)
    suffix = {"new": "new release official audio", "calm": "acoustic chill music", "energy": "upbeat live music"}.get(mode, "official audio music")
    if expansion:
        suffix = ["live session music", "acoustic music", "new songs", "collaboration music", "album audio"][(expansion-1) % 5]
    queries = [f"{artist} {suffix}" for artist in artists]
    if not queries:
        queries = ["new music official audio", "independent artists official audio", "instrumental music official audio"]
    if use_ai:
        queries = list(radio.profile().get('queries', [])) + queries
    return queries


def discovery_page(query, seed, page, mode, excluded):
    key = (query, seed, mode)
    now = time.time()
    with discovery_lock:
        for old in list(discovery_sessions):
            if now - discovery_sessions[old]["created"] > 7200:
                discovery_sessions.pop(old)
        if key not in discovery_sessions:
            if len(discovery_sessions) >= 100:
                discovery_sessions.pop(next(iter(discovery_sessions)))
            discovery_sessions[key] = {"created": now, "lock": threading.Lock(), "pages": {}, "seen": set(excluded),
                "queries": [query] if query else discovery_queries(seed, mode), "cursor": 0, "exhausted": set(), "pending": [], "more": True, "expansion": 0}
        session = discovery_sessions[key]
    with session["lock"]:
        if page in session["pages"]:
            return session["pages"][page]
        if page != len(session["pages"]):
            raise HTTPException(409, "正在恢复发现列表，请重试加载")
        session["seen"].update(excluded)
        # Fetch independent artist searches together, and interleave their results.
        for _ in range(4):
            if len(session["pending"]) >= 18:
                break
            if not session["more"]:
                if query or session["expansion"] >= 5:
                    break
                session["expansion"] += 1
                session["queries"] = discovery_queries(seed, mode, session["expansion"])
                session["cursor"] = 0
                session["exhausted"].clear()
                session["more"] = True
            count = len(session["queries"])
            work = []
            cursor_before = session["cursor"]
            for __ in range(min(3, count)):
                if len(session["exhausted"]) == count:
                    session["more"] = False
                    break
                while session["cursor"] % count in session["exhausted"]:
                    session["cursor"] += 1
                index = session["cursor"] % count
                source_page = session["cursor"] // count
                work.append((index, source_page))
                session["cursor"] += 1
            if not work:
                break
            try:
                with ThreadPoolExecutor(max_workers=3) as pool:
                    replies = list(pool.map(lambda pair: search_youtube(session["queries"][pair[0]], pair[1]), work))
            except Exception:
                session["cursor"] = cursor_before
                raise
            for (index, _), (_, more) in zip(work, replies):
                if not more:
                    session["exhausted"].add(index)
            for i in range(max((len(rows) for rows, _ in replies), default=0)):
                for rows, _ in replies:
                    if i >= len(rows):
                        continue
                    song = rows[i]
                    if song["id"] not in session["seen"]:
                        session["seen"].add(song["id"])
                        session["pending"].append(song)
            session["more"] = len(session["exhausted"]) < count
        rows, session["pending"] = session["pending"][:18], session["pending"][18:]
        more = bool(session["pending"]) or session["more"] or (not query and session["expansion"] < 5)
        result = {"tracks": rows, "seed": seed, "page": page, "nextPage": page + 1 if more else None,
                  "hasMore": more, "query": query, "mode": mode}
        session["pages"][page] = result
        return result


def publish_import(job, source, automatic):
    # Serialize capacity accounting and indexing; downloads may finish concurrently.
    with publish_lock:
        if automatic and radio.used() + source.stat().st_size > radio.config()['budgetBytes']:
            raise RuntimeError('新歌池容量已满，保留歌曲不自动删除')
        destination = ROOT / ("Serein Discoveries" if automatic else "Serein Imports")
        destination.mkdir(exist_ok=True)
        target = destination / (job["video"] + ".m4a")
        import shutil
        partial = target.with_suffix(".partial")
        shutil.copyfile(source, partial)
        partial.replace(target)
        scan(wait=True)
        tid = hashlib.sha256(target.relative_to(ROOT).as_posix().encode()).hexdigest()[:24]
        with db() as c:
            c.execute("UPDATE jobs SET status='complete',progress=1,track=? WHERE id=?", (tid, job["id"]))
            radio.completed(c, job, tid)


def import_job(job):
    stage = DATA / ".staging" / job["id"]
    stage.mkdir(parents=True, exist_ok=True)
    with db() as c:
        c.execute("UPDATE jobs SET status='downloading',error='',progress=0 WHERE id=?", (job["id"],))
    try:
        automatic = job.get('origin') == 'pool'
        check_automatic = automatic and not job.get('visible', 1)
        expected_artist = ''
        if check_automatic:
            status, reason = quality.check(job['video'])
            if status != 'approved':
                with db() as c:
                    c.execute('UPDATE jobs SET status=?,error=? WHERE id=?', (status, reason, job['id']))
                return
            with db() as c:
                entry = c.execute('SELECT metadata FROM pool_entries WHERE video=?',(job['video'],)).fetchone()
                check = c.execute('SELECT metadata FROM content_checks WHERE video=?',(job['video'],)).fetchone()
            expected_artist = json.loads(entry[0]).get('expectedArtist','') if entry else ''
            if expected_artist and (not check or not artist_matches(expected_artist,json.loads(check[0]))):
                with db() as c:
                    c.execute("UPDATE jobs SET status='rejected',error=? WHERE id=?",
                              ('搜索结果的发行署名或认证频道与目标歌手不符，未加入个性化候选',job['id']))
                return
        cmd = yt_command() + ["--no-progress", "--max-filesize", "300M", "--match-filter", "!is_live",
            "-f", "bestaudio/best", "-x", "--audio-format", "m4a", "--audio-quality", "0", "--embed-metadata",
            "--embed-thumbnail", "--write-info-json", "-o", str(stage / "%(id)s.%(ext)s"),
            "--", "https://www.youtube.com/watch?v=" + job["video"]]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        outputs = list(stage.glob("*.m4a"))
        if result.returncode or not outputs:
            error = result.stderr.strip().splitlines()[-1] if result.stderr.strip() else "音源不可用或超过下载容量限制"
            raise RuntimeError(error[-600:])
        if check_automatic:
            info_files = list(stage.glob('*.info.json'))
            if not info_files:
                raise RuntimeError('下载后缺少音源资料，未加入自动音乐池')
            info = json.loads(info_files[0].read_text(encoding='utf-8'))
            status, reason = quality.record(job['video'], *quality.verdict(job['video'], info), info)
            if status != 'approved':
                with db() as c:
                    c.execute('UPDATE jobs SET status=?,error=? WHERE id=?', (status, reason, job['id']))
                return
            if expected_artist and not artist_matches(expected_artist,info):
                raise RuntimeError('下载后的发行署名与目标歌手不符，未加入个性化候选')
        publish_import(job, outputs[0], automatic)
        import shutil
        shutil.rmtree(stage)
    except Exception as e:
        with db() as c:
            c.execute("UPDATE jobs SET status='failed',error=? WHERE id=?", (str(e)[:600], job["id"]))
    finally:
        import shutil
        if stage.is_dir() and stage.resolve().parent == (DATA / '.staging').resolve():
            shutil.rmtree(stage)


def claim_import_job():
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        job = c.execute("SELECT * FROM jobs WHERE status='queued' AND (visible=1 OR ?) ORDER BY visible DESC,created LIMIT 1", (radio.config()['enabled'],)).fetchone()
        if job:
            c.execute("UPDATE jobs SET status='downloading' WHERE id=?", (job['id'],))
            return dict(job)


def import_loop(stop):
    last_scan = 0.0
    active = set()
    with ThreadPoolExecutor(max_workers=3) as workers:
        while not stop.wait(2):
            try:
                if time.time() - last_scan > 300:
                    scan()
                    last_scan = time.time()
                finished = {f for f in active if f.done()}
                active -= finished
                for future in finished:
                    future.result()
                if len(active) < 3:
                    job = claim_import_job()
                    if job:
                        active.add(workers.submit(import_job, job))
            except Exception:
                import logging
                logging.exception("Music maintenance failed")


@asynccontextmanager
async def lifespan(app):
    AIConfig.from_env()
    AIConfig.from_env(audio=True)
    ROOT.mkdir(parents=True, exist_ok=True)
    initialize()
    if not radio.get('instanceId'):
        radio.put('instanceId', str(uuid.uuid4()))
    scan()
    with db() as c:
        c.execute("UPDATE jobs SET status='queued' WHERE status='downloading'")
    stop = threading.Event()
    thread = threading.Thread(target=import_loop, args=(stop,), daemon=True)
    thread.start()
    radio_thread = threading.Thread(target=radio.loop, args=(stop,), daemon=True)
    radio_thread.start()
    threading.Thread(target=traits.loop, args=(stop,), daemon=True).start()
    threading.Thread(target=charts.loop, args=(stop,), daemon=True).start()
    yield
    stop.set()
    radio.wake.set()


quality = ContentGuard(sys.modules[__name__])
seeds = Seeds(sys.modules[__name__])
similar = Similar(sys.modules[__name__])
radio = Radio(sys.modules[__name__])
history = History(sys.modules[__name__])
mixes = Mixes(sys.modules[__name__])
traits = AudioTraits(sys.modules[__name__])
charts = Charts(sys.modules[__name__])
app = FastAPI(title="Echo", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/music/api/health")
def health():
    return {"ok": True, "version": "0.9.0", "access": "direct", "instanceId": radio.get('instanceId', ''),
            "aiConfigured": AIConfig.from_env().enabled, "audioConfigured": AIConfig.from_env(audio=True).enabled}


@app.get("/music/api/library")
def get_library():
    with db() as c:
        disliked = sorted(radio.disliked(c))
    # Phones drop disliked songs from automatic offline copies; manual keeps are untouched.
    return {"tracks": library(), "updated": time.time(), 'excluded': list(quality.excluded()), 'disliked': disliked}


@app.post("/music/api/scan")
def rescan():
    scan()
    return {"count": len(library())}


def find_track(tid):
    with db() as c:
        row = c.execute("SELECT * FROM tracks WHERE id=?", (tid,)).fetchone()
    if not row:
        raise HTTPException(404, "歌曲已从服务器移除")
    path = (ROOT / row["path"]).resolve()
    if not path.is_relative_to(ROOT) or not path.is_file():
        raise HTTPException(404, "歌曲文件不可用")
    return row, path


@app.api_route("/music/api/tracks/{tid}/audio", methods=["GET", "HEAD"])
def audio(tid: str, request: Request):
    _, path = find_track(tid)
    if request.method == 'GET':
        radio.access(tid)
    return FileResponse(path, headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"})


@app.get("/music/api/tracks/{tid}/cover")
def cover(tid: str):
    find_track(tid)
    path = DATA / "covers" / tid
    if not path.is_file():
        raise HTTPException(404, "没有封面")
    with path.open('rb') as image:
        signature = image.read(12)
    mime = "image/png" if signature.startswith(b"\x89PNG") else "image/webp" if signature.startswith(b"RIFF") else "image/jpeg"
    return FileResponse(path, media_type=mime, headers={"Cache-Control": "private, max-age=86400"})


class Listen(BaseModel):
    event: str = Field(min_length=10, max_length=80)
    track: str = Field(max_length=30)
    seconds: float = Field(ge=0, le=86400)
    at: float = Field(ge=0)


@app.post("/music/api/listens")
def listen(body: Listen):
    row, _ = find_track(body.track)
    threshold = min(30.0, row["duration"] / 2) if row["duration"] else 30.0
    if body.seconds >= threshold:
        with db() as c:
            history.remember(c, row)
            c.execute("INSERT OR IGNORE INTO listens VALUES(?,?,?,?)",
                      (body.event, body.track, body.seconds, min(time.time(), body.at)))
            radio.promote(c, body.track, heard=True)
    return {"ok": True}


class Favorite(BaseModel):
    liked: bool
    updated: float = Field(ge=0)


@app.put("/music/api/tracks/{tid}/favorite")
def favorite(tid: str, body: Favorite):
    find_track(tid)
    with db() as c:
        c.execute('''INSERT INTO favorites VALUES(?,?,?) ON CONFLICT(track) DO UPDATE
          SET liked=excluded.liked,updated=excluded.updated WHERE excluded.updated>=favorites.updated''',
                  (tid, body.liked, min(body.updated, time.time())))
        radio.promote(c, tid)
    return {"ok": True}


@app.get("/music/api/recommendations")
def recommend(seed: str = Query(default="", max_length=80)):
    return {"tracks": recommendations(seed=seed)}


@app.get("/music/api/discover")
def discover(q: str = Query(default="", max_length=150), seed: str = Query(default="", max_length=80),
             page: int = Query(default=0, ge=0, le=10000), mode: str = Query(default="taste", pattern="^(taste|new|calm|energy)$"),
             exclude: str = Query(default="", max_length=12000)):
    query = q.strip()
    seed = seed or str(uuid.uuid4())
    excluded = {v for v in exclude.split(",") if re.fullmatch(r"[A-Za-z0-9_-]{11}", v)}
    try:
        result = discovery_page(query, seed, page, mode, excluded)
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "寻找音乐用时较长，请稍后重试")
    with db() as c:
        jobs = {r["video"]: dict(r) for r in c.execute("SELECT * FROM jobs")}
    known = {s['id']: s for s in library()}
    return {**result, "tracks": [{**song, "job": jobs.get(song["id"]),
              'song': known.get(jobs.get(song['id'], {}).get('track', ''))} for song in result["tracks"]]}


class DiscoveryRequest(BaseModel):
    q: str = Field(default="", max_length=150)
    seed: str = Field(default="", max_length=80)
    page: int = Field(default=0, ge=0, le=10000)
    mode: str = Field(default="taste", pattern="^(taste|new|calm|energy)$")
    exclude: list[str] = Field(default_factory=list, max_length=10000)


@app.post("/music/api/discover")
def discover_post(body: DiscoveryRequest):
    # Seen IDs travel in the body so long listening sessions cannot hit URL limits.
    if not body.q.strip():
        return radio.ready(body.seed or str(uuid.uuid4()), body.page, body.mode, set(body.exclude))
    return discover(body.q, body.seed, body.page, body.mode, ",".join(body.exclude))


class ImportRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    title: str = Field(default="", max_length=300)


@app.post("/music/api/imports")
def create_import(body: ImportRequest):
    try:
        vid = video_id(body.url.strip())
    except ValueError as e:
        raise HTTPException(400, str(e))
    with db() as c:
        job = c.execute("SELECT * FROM jobs WHERE video=?", (vid,)).fetchone()
        if job:
            c.execute('UPDATE jobs SET visible=1 WHERE id=?', (job['id'],))
            if job['origin'] == 'pool':
                c.execute("UPDATE pool_entries SET state='resident',retire_at=0 WHERE video=?", (vid,))
            missing = False
            if job["status"] == "complete":
                try:
                    find_track(job["track"])
                except HTTPException:
                    missing = True
            if job["status"] in ("failed", "rejected", "review") or missing:
                c.execute("UPDATE jobs SET status='queued',error='' WHERE id=?", (job["id"],))
            return {"id": job["id"], "status": "queued" if job["status"] in ("failed", "rejected", "review") or missing else job["status"]}
        if c.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','downloading')").fetchone()[0] >= 20:
            raise HTTPException(429, "已有 20 首歌曲等待入库，请稍后再加入")
        jid = str(uuid.uuid4())
        c.execute("INSERT INTO jobs(id,video,title,status,created) VALUES(?,?,?,?,?)", (jid, vid, body.title or vid, "queued", time.time()))
    return {"id": jid, "status": "queued"}


@app.get("/music/api/imports")
def imports():
    with db() as c:
        return {"jobs": [dict(r) for r in c.execute("SELECT * FROM jobs WHERE visible=1 ORDER BY created DESC LIMIT 100")]}


class PlaybackEvent(BaseModel):
    event: str = Field(min_length=10, max_length=80)
    track: str = Field(min_length=1, max_length=30)
    seconds: float = Field(ge=0, le=86400, allow_inf_nan=False)
    position: float = Field(ge=0, le=86400, allow_inf_nan=False)
    duration: float = Field(ge=0, le=86400, allow_inf_nan=False)
    started: float = Field(ge=0, allow_inf_nan=False)
    at: float = Field(ge=0, allow_inf_nan=False)
    outcome: Literal['progress','completed','skipped','stopped','error']
    seq: int = Field(ge=1, le=1000000)


@app.post('/music/api/playback')
def playback(body: PlaybackEvent):
    return radio.event(body)


@app.get('/music/api/history')
def playback_history(cursor: str = Query(default='', max_length=500),
                     limit: int = Query(default=50, ge=1, le=100), q: str = Query(default='', max_length=150)):
    return history.page(cursor, limit, q.strip())


@app.get('/music/api/charts')
def chart_sources():
    return {'sources': [{'provider': p, 'regions': list(regions)} for p, regions in REGIONS.items()],
            'echoPeriods': [7, 30, 0]}


@app.get('/music/api/charts/echo')
def echo_chart(days: int = Query(default=7)):
    if days not in (0, 7, 30):
        raise HTTPException(422, '請選擇 7、30 或 0 天 / Select 7, 30 or 0 days')
    return charts.ranking(days)


@app.get('/music/api/charts/{provider}/{region}')
def external_chart(provider: str, region: str, refresh: bool = False):
    return charts.chart(provider, region, refresh)


@app.get('/music/api/artists/search')
def find_artists(q: str = Query(min_length=1, max_length=120), country: Literal['hk', 'tw', 'jp'] = 'hk'):
    try:
        return charts.search_artists(q, country)
    except Exception:
        raise HTTPException(502, '歌手資料暫時無法連線 / Artist catalogue unavailable') from None


@app.get('/music/api/artists')
def followed_artists():
    return {'artists': charts.artists()}


class ArtistFollow(BaseModel):
    country: Literal['hk', 'tw', 'jp'] = 'hk'
    followed: bool


@app.put('/music/api/artists/{artist}')
def follow_artist(body: ArtistFollow, artist: str = ApiPath(pattern=r'^\d{1,20}$')):
    try:
        return charts.follow(artist, body.country, body.followed)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(502, '歌手資料暫時無法連線 / Artist catalogue unavailable') from None


@app.get('/music/api/releases')
def artist_releases(refresh: bool = False, offset: int = Query(default=0, ge=0, le=10000),
                    limit: int = Query(default=50, ge=1, le=100)):
    return charts.releases(refresh, offset, limit)


@app.get('/music/api/mixes')
def playlist_editions():
    return mixes.feed()


class MixConfig(BaseModel):
    intervalHours: Literal[2, 6, 24]


@app.put('/music/api/mixes/config')
def configure_mixes(body: MixConfig):
    return mixes.configure(body.intervalHours)


class MixSave(BaseModel):
    saved: bool
    updated: float = Field(ge=0, allow_inf_nan=False)


@app.put('/music/api/mixes/{mid}/saved')
def save_mix(mid: str, body: MixSave):
    return mixes.save(mid, body.saved, body.updated)


class PoolConfig(BaseModel):
    enabled: bool | None = None
    target: int | None = Field(default=None, ge=18, le=300)
    dailyPercent: int | None = Field(default=None, ge=5, le=50)
    residentPlays: int | None = Field(default=None, ge=2, le=20)
    budgetBytes: int | None = Field(default=None, ge=268435456, le=107374182400)
    aiEnabled: bool | None = None


@app.get('/music/api/pool')
def pool_status():
    return radio.status()


@app.put('/music/api/pool')
def pool_config(body: PoolConfig):
    return radio.configure(body.model_dump(exclude_none=True))


@app.post('/music/api/pool/prepare')
def pool_prepare():
    radio.next_fill = 0
    radio.wake.set()
    return {'ok': True}


@app.post('/music/api/pool/tracks/{tid}/keep')
def pool_keep(tid: str):
    find_track(tid)
    return radio.keep(tid)


@app.post('/music/api/pool/tracks/{tid}/report-content')
def pool_report_content(tid: str):
    return quality.report(tid)

@app.get("/", include_in_schema=False)
def root_page():
    return RedirectResponse("/music")

@app.get("/music", include_in_schema=False)
@app.get("/music/", include_in_schema=False)
def setup_page():
    return FileResponse(Path(__file__).resolve().parents[1] / 'web' / 'index.html')

app.mount("/music/assets", StaticFiles(directory=Path(__file__).resolve().parents[1] / 'web'), name="assets")
