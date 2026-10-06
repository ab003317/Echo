import json
import time
from server.test_app import api
from server.similar import neighbours, artist_keys, song_keys, healthy
from server.content import evaluate, evaluate_trusted


def track(video, title='', artists=('X',), kind='MUSIC_VIDEO_TYPE_ATV'):
    return {'id': video, 'title': title or video, 'artists': list(artists), 'videoType': kind}


def radio(seed, *videos):
    return [track(seed)] + [track(v) for v in videos]


def test_artist_keys_ignore_channel_suffixes_and_split_credits():
    assert 'reol' in artist_keys('Reol Official')
    assert 'mythroid' in artist_keys('MYTH & ROID Official Channel')
    assert {'soundholic', 'nanatakahashi'} <= artist_keys('SOUND HOLIC, Nana Takahashi')
    assert 'mili' in artist_keys(['Mili', 'Joyia'])
    assert song_keys('虚星獣 - Ethereal Beast', ['sasakure.UK']) & song_keys('虚星獣', 'sasakure.UK')


def test_neighbours_weight_support_position_and_skip_known():
    radios = {'seed0000001': radio('seed0000001', 'shared00001', 'late0000001'),
              'seed0000002': radio('seed0000002', 'shared00001', 'other000001'),
              'seed0000003': [track('generic0001')] + radio('seed0000003', 'shared00001')[1:]}
    ranked = neighbours(radios, {'seed0000001': 1, 'seed0000002': 3, 'seed0000003': 1})
    ids = [t['id'] for t in ranked]
    assert ids[0] == 'shared00001' and ranked[0]['support'] == 2
    # The third radio does not start with its seed, so it is a generic feed and ignored.
    assert 'generic0001' not in ids
    assert ids.index('other000001') < ids.index('late0000001')
    known = song_keys('other000001', ['X'], 'other000001')
    assert 'other000001' not in [t['id'] for t in neighbours(radios, {'seed0000001': 1, 'seed0000002': 3}, known)]


def test_promotions_shared_by_most_radios_are_dropped():
    radios = {f's{i:010d}': radio(f's{i:010d}', 'promo000001', f'n{i:010d}') for i in range(12)}
    ranked = neighbours(radios, {seed: 1 for seed in radios})
    assert 'promo000001' not in [t['id'] for t in ranked] and len(ranked) == 12
    assert healthy('s0000000000', radios['s0000000000'])


def test_artist_channel_upload_is_a_song_but_karaoke_is_not():
    base = {'duration': 194, 'channel_is_verified': True, 'categories': ['Music'], 'description': ''}
    assert evaluate({**base, 'title': 'Mili - Lemonade', 'channel': 'Mili'})[0] == 'approved'
    assert evaluate({**base, 'title': '【カラオケ】ANIMA / ReoNa', 'channel': 'カラオケ歌っちゃ王'})[0] == 'review'
    assert evaluate({**base, 'title': 'Mili - Lemonade 1 hour loop', 'channel': 'Mili'})[0] == 'review'
    assert evaluate({**base, 'title': 'Mili - Lemonade', 'channel': 'Mili', 'duration': 3600})[0] == 'review'
    assert evaluate({**base, 'channel_is_verified': None, 'title': 'Mili - Lemonade', 'channel': 'Mili'})[0] == 'review'


def test_trusted_sources_keep_format_and_live_rules():
    assert evaluate_trusted({'title': '夜天の檻 short mix'}, 'list')[0] == 'approved'
    assert evaluate_trusted({'title': 'Song (Teaser)'}, 'radio')[0] == 'rejected'
    assert evaluate_trusted({'title': 'Teaser', 'track': 'Teaser'}, 'radio')[0] == 'approved'
    assert evaluate_trusted({'title': 'Song', 'is_live': True}, 'list')[0] == 'rejected'


def store_radios(a, radios):
    with a.db() as c:
        for seed, tracks in radios.items():
            c.execute("INSERT OR REPLACE INTO similar_radios VALUES(?,?,'ok',?)", (seed, time.time(), json.dumps(tracks)))


def test_fill_queues_list_and_radio_songs_with_provenance(api, monkeypatch):
    c, a = api
    a.seeds.import_csv('Video ID,Song Title,Album Title,Artist Name 1\n'
                       'listsong001,Listed,Album,Liked\nlistsong002,Another,Album,Liked\n', 'songs.csv')
    store_radios(a, {'listsong001': radio('listsong001', 'neighbor001', 'listsong002'),
                     'listsong002': radio('listsong002', 'neighbor001', 'neighbor002')})
    monkeypatch.setattr(a, 'search_youtube', lambda *args, **kw: ([], False))
    a.radio.fill()
    with a.db() as con:
        entries = {r[0]: json.loads(r[1]) for r in con.execute('SELECT video,metadata FROM pool_entries')}
    assert {v for v, m in entries.items() if m['source'] == 'list'} == {'listsong001', 'listsong002'}
    radio_songs = {v for v, m in entries.items() if m['source'] == 'radio'}
    # List songs already queued as list entries are never repeated as radio neighbours.
    assert radio_songs == {'neighbor001', 'neighbor002'}
    assert entries['neighbor001']['videoType'] == 'MUSIC_VIDEO_TYPE_ATV'
    assert a.radio.source_counts() == {'list': 2, 'radio': 2}


def test_plan_follows_source_shares():
    from server.radio import Radio
    assert Radio.plan({}, 8, 60) == {'radio': 8}
    assert Radio.plan({'radio': 33, 'list': 0, 'search': 12}, 8, 60) == {'list': 8}
    slots = Radio.plan({'radio': 30, 'list': 14, 'search': 8}, 8, 60)
    assert slots['search'] >= 3 and sum(slots.values()) == 8


def test_trusted_list_song_skips_metadata_review(api, monkeypatch):
    c, a = api
    a.radio.queue_candidates([{'id': 'listsong001', 'title': 'Listed', 'source': 'list'},
                              {'id': 'searched001', 'title': 'Found', 'source': 'search'}], 2)
    info = {'title': 'Untitled upload', 'duration': 200, 'categories': ['People & Blogs'], 'channel': 'fan'}
    monkeypatch.setattr(a, 'youtube_info', lambda video: {**info, 'id': video})
    assert a.quality.check('listsong001') == ('approved', '你提供的口味清单歌曲')
    assert a.quality.check('searched001')[0] == 'review'


def test_listed_and_similar_songs_rank_first(api):
    c, a = api
    for name in ('Listed', 'Near', 'Far'):
        (a.ROOT / f'Band_{name}.wav').write_bytes((a.ROOT / 'Artist_Song.wav').read_bytes())
    a.scan(wait=True)
    a.seeds.import_csv('Video ID,Song Title,Album Title,Artist Name 1\nlistsong001,Listed,Album,Band\n', 'songs.csv')
    near = track('nearsong001', 'Near', ['Band'])
    store_radios(a, {'listsong001': [track('listsong001', 'Listed', ['Band']), near]})
    order = [s['title'] for s in a.recommendations(seed='fixed')]
    assert order.index('Listed') < order.index('Far') and order.index('Near') < order.index('Far')
    reasons = {s['title']: s['reason'] for s in a.recommendations(seed='fixed')}
    assert reasons['Listed'] == '来自你的口味清单' and reasons['Near'] == '与你喜欢的歌相似'


class FakeMusic:
    def __init__(self):
        self.calls = []

    def get_watch_playlist(self, videoId, radio, limit):
        self.calls.append(videoId)
        first = videoId if videoId != 'blocked0001' else 'promo000001'
        return {'tracks': [{'videoId': first, 'title': 'T', 'artists': [{'name': 'A'}], 'videoType': 'MUSIC_VIDEO_TYPE_ATV'},
                           {'videoId': 'neighbor001', 'title': 'N', 'artists': [{'name': 'B'}]}]}

    def search(self, query, filter, limit):
        # Same title by someone else, then the right song under a localized credit but equal length.
        return [{'videoId': 'wrongsong01', 'title': 'Other', 'artists': [{'name': 'Artist'}], 'duration_seconds': 40},
                {'videoId': 'samename001', 'title': 'Song', 'artists': [{'name': 'Other'}], 'duration_seconds': 200},
                {'videoId': 'resolved001', 'title': 'Song - English', 'artists': [{'name': 'アーティスト'}], 'duration_seconds': 41}]


def test_refresh_fetches_due_radios_and_resolves_liked_local_files(api, monkeypatch):
    c, a = api
    fake = FakeMusic()
    monkeypatch.setattr(a.similar, 'client', lambda: fake)
    a.seeds.import_csv('Video ID,Song Title,Album Title,Artist Name 1\nlistsong001,L,Al,A\nblocked0001,B,Al,A\n', 'songs.csv')
    tid = a.library()[0]['id']
    c.put(f'/music/api/tracks/{tid}/favorite', json={'liked': True, 'updated': time.time()})
    assert a.similar.refresh(budget=10, pause=0) == 3
    with a.db() as con:
        status = {r[0]: r[1] for r in con.execute('SELECT video,status FROM similar_radios')}
        lookup = con.execute('SELECT video FROM similar_lookups WHERE track=?', (tid,)).fetchone()[0]
    assert status == {'listsong001': 'ok', 'blocked0001': 'unavailable'} and lookup == 'resolved001'
    # The liked local file now seeds its own radio; fresh radios are not fetched again.
    assert a.similar.refresh(budget=10, pause=0) == 1 and fake.calls[-1] == 'resolved001'
    assert a.similar.refresh(budget=10, pause=0) == 0
