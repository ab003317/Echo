import importlib
import os
import wave

import pytest
from fastapi.testclient import TestClient


@pytest.fixture()
def api(tmp_path, monkeypatch):
    root = tmp_path / "music"
    root.mkdir()
    with wave.open(str(root / "Artist_Song.wav"), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(8000)
        f.writeframes(b"\0\0" * 8000 * 40)
    monkeypatch.setenv("SEREIN_MUSIC_ROOT", str(root))
    monkeypatch.setenv("SEREIN_DATA_DIR", str(tmp_path / "data"))
    from server import app
    app = importlib.reload(app)
    app.initialize()
    app.scan()
    client = TestClient(app.app)
    return client, app


def test_direct_access_range_and_no_absolute_paths(api):
    c, a = api
    songs = c.get("/music/api/library").json()["tracks"]
    assert len(songs) == 1 and "path" not in songs[0]
    tid = songs[0]["id"]
    res = c.get(f"/music/api/tracks/{tid}/audio", headers={"Range": "bytes=10-29"})
    assert res.status_code == 206 and len(res.content) == 20
    assert res.headers["content-range"].startswith("bytes 10-29/")
    assert c.get(f"/music/api/tracks/{tid}/audio", headers={"Range": "bytes=999999999-"}).status_code == 416
    for endpoint in ["library", f"tracks/{tid}/audio", "imports", "recommendations"]:
        assert c.get("/music/api/" + endpoint).status_code == 200
    assert not (a.DATA / "access.key").exists()


def test_listen_threshold_dedup_and_favorite(api):
    c, a = api
    tid = a.library()[0]["id"]
    body = {"track": tid, "event": "event-test-123", "seconds": 1, "at": 100}
    c.post("/music/api/listens", json=body)
    assert a.library()[0]["plays"] == 0
    body["seconds"] = 30
    c.post("/music/api/listens", json=body)
    c.post("/music/api/listens", json=body)
    assert a.library()[0]["plays"] == 1
    c.put(f"/music/api/tracks/{tid}/favorite", json={"liked": True, "updated": 200})
    c.put(f"/music/api/tracks/{tid}/favorite", json={"liked": False, "updated": 100})
    assert a.library()[0]["favorite"] == 1


def test_import_validation_dedup_retry(api):
    c, a = api
    for url in ["http://youtube.com/watch?v=abcdefghijk", "https://localhost/audio", "https://youtube.com.evil/watch?v=abcdefghijk", "https://youtube.com/playlist?list=abc", "file:///etc/passwd"]:
        assert c.post("/music/api/imports", json={"url": url}).status_code == 400
    r = c.post("/music/api/imports", json={"url": "https://music.youtube.com/watch?v=abcdefghijk"}).json()
    r2 = c.post("/music/api/imports", json={"url": "https://youtu.be/abcdefghijk"}).json()
    assert r["id"] == r2["id"]
    with a.db() as con:
        con.execute("UPDATE jobs SET status='failed' WHERE id=?", (r["id"],))
    assert c.post("/music/api/imports", json={"url": "abcdefghijk"}).json()["status"] == "queued"


def test_scan_missing_file_and_path_escape(api):
    c, a = api
    tid = a.library()[0]["id"]
    with a.db() as con:
        con.execute("UPDATE tracks SET path='../data/access.key' WHERE id=?", (tid,))
    assert c.get(f"/music/api/tracks/{tid}/audio").status_code == 404
    (a.ROOT / "Artist_Song.wav").unlink()
    a.scan()
    assert a.library() == []


def test_discovery_pagination_dedup_stable_and_refresh(api, monkeypatch):
    c, a = api
    def search(query, page=0, size=18):
        # One overlapping item between pages, as commonly returned by search providers.
        base = page * 17
        return ([{"id": f"v{i:010}", "title": f"Song {i}", "artist": query, "duration": 180, "cover": ""}
                 for i in range(base, base + 18)], page < 5)
    monkeypatch.setattr(a, "search_youtube", search)
    pages = [c.get(f"/music/api/discover?q=Artist&seed=session&page={p}").json() for p in range(3)]
    ids = [s["id"] for p in pages for s in p["tracks"]]
    assert len(ids) == 54 and len(set(ids)) == 54
    assert c.get("/music/api/discover?q=Artist&seed=session&page=1").json() == pages[1]
    refreshed = c.get("/music/api/discover", params={"q": "Artist", "seed": "refresh", "exclude": ",".join(ids)}).json()
    assert not (set(ids) & {s["id"] for s in refreshed["tracks"]})
    assert refreshed["tracks"]


def test_discovery_exhaustion_and_error_retry(api, monkeypatch):
    c, a = api
    attempts = []
    def search(query, page=0, size=18):
        attempts.append(page)
        if len(attempts) == 1:
            raise a.HTTPException(502, "temporary")
        return ([{"id": "abcdefghijk", "title": "Only", "artist": query}], False)
    monkeypatch.setattr(a, "search_youtube", search)
    url = "/music/api/discover?q=Artist&seed=retry"
    assert c.get(url).status_code == 502
    result = c.get(url).json()
    assert attempts == [0, 0]
    assert len(result["tracks"]) == 1 and result["hasMore"] is False and result["nextPage"] is None


def test_completed_import_missing_track_can_retry(api):
    c, a = api
    r = c.post("/music/api/imports", json={"url": "abcdefghijk"}).json()
    with a.db() as con:
        con.execute("UPDATE jobs SET status='complete',track='gone' WHERE id=?", (r["id"],))
    assert c.post("/music/api/imports", json={"url": "abcdefghijk"}).json()["status"] == "queued"


def test_completed_import_deleted_file_before_scan_can_retry(api):
    c, a = api
    r = c.post("/music/api/imports", json={"url": "abcdefghijk"}).json()
    with a.db() as con:
        con.execute("UPDATE jobs SET status='complete',track=? WHERE id=?", (a.library()[0]['id'], r['id']))
    (a.ROOT / 'Artist_Song.wav').unlink()
    assert c.post('/music/api/imports', json={'url': 'abcdefghijk'}).json()['status'] == 'queued'


def test_discovery_expands_sources_without_restarting_feed(api, monkeypatch):
    c, a = api
    monkeypatch.setattr(a, 'discovery_queries', lambda seed, mode, expansion=0: [f'source-{expansion}'])
    def search(query, page=0, size=18):
        batch = int(query.split('-')[1])
        return ([{'id': f'v{batch*18+i:010}', 'title': str(i)} for i in range(18)], False)
    monkeypatch.setattr(a, 'search_youtube', search)
    pages=[c.get(f'/music/api/discover?seed=expansion&page={page}').json() for page in range(4)]
    ids=[song['id'] for page in pages for song in page['tracks']]
    assert len(ids)==72 and len(set(ids))==72
    assert all(page['hasMore'] for page in pages)


def test_post_discovery_accepts_long_seen_history_without_url_limits(api, monkeypatch):
    c, a = api
    seen=[f'v{i:010}' for i in range(1500)]
    monkeypatch.setattr(a, 'search_youtube', lambda *args: ([{'id':'fresh000001','title':'New'}],False))
    response=c.post('/music/api/discover',json={'q':'Artist','seed':'long-history','exclude':seen})
    assert response.status_code==200
    assert response.json()['tracks'][0]['id']=='fresh000001'
    assert set(seen)<=a.discovery_sessions[('Artist','long-history','taste')]['seen']
