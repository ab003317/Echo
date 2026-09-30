"""Rotating, immutable playlist editions with durable saved snapshots."""
from collections import defaultdict
import json
import math
import random
import re
import threading
import time
import uuid

GENRES={'electronic_pop':'电子流行','rock':'摇滚','hip_hop':'嘻哈','orchestral':'管弦',
        'jazz':'爵士','acoustic':'原声','ambient':'氛围','dance':'舞曲','metal':'金属','folk':'民谣'}


def series_group(album):
    if not album or album.casefold() in ('unknown','single','未分类专辑'):
        return None
    for pattern,label in [(r'under night in-birth','UNDER NIGHT IN-BIRTH'),(r'library of ruina','Library of Ruina'),
                          (r'caligula|カリギュラ','Caligula'),(r'persona|ペルソナ','Persona'),
                          (r'arcaea','Arcaea'),(r'genshin|原神','原神'),(r'学園アイドルマスター|gakuen idolmaster','学园偶像大师')]:
        if re.search(pattern,album,re.I):
            return label,'同系列发行'
    return album,'同专辑'


class Mixes:
    def __init__(self,app):
        self.a=app
        self.lock=threading.RLock()

    def initialize(self):
        with self.a.db() as c:
            c.executescript('''
                CREATE TABLE IF NOT EXISTS mix_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS mix_editions(id TEXT PRIMARY KEY,kind TEXT NOT NULL,group_key TEXT NOT NULL,
                    title TEXT NOT NULL,description TEXT NOT NULL,created REAL NOT NULL,expires REAL NOT NULL,
                    saved INTEGER NOT NULL DEFAULT 0,saved_at REAL NOT NULL DEFAULT 0,songs TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS saved_mix_tracks(mix TEXT NOT NULL,track TEXT NOT NULL,PRIMARY KEY(mix,track));
                CREATE INDEX IF NOT EXISTS saved_mix_track ON saved_mix_tracks(track);
                CREATE TABLE IF NOT EXISTS audio_features(track TEXT PRIMARY KEY,record TEXT NOT NULL,created REAL NOT NULL);
            ''')

    def get(self,key,default=None):
        with self.a.db() as c:
            row=c.execute('SELECT value FROM mix_meta WHERE key=?',(key,)).fetchone()
        return json.loads(row[0]) if row else default

    def put(self,c,key,value):
        c.execute('INSERT INTO mix_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',(key,json.dumps(value)))

    def protected(self,c,track):
        return bool(c.execute('SELECT 1 FROM saved_mix_tracks WHERE track=? LIMIT 1',(track,)).fetchone())

    def configure(self,hours):
        if hours not in (2,6,24):
            raise self.a.HTTPException(422,'请选择 2、6 或 24 小时')
        with self.lock,self.a.db() as c:
            self.put(c,'intervalHours',hours)
            next_at=time.time()+hours*3600
            current=self.get('currentIds',[])
            for mid in current:
                c.execute('UPDATE mix_editions SET expires=? WHERE id=?',(next_at,mid))
            self.put(c,'nextRefresh',next_at)
        return self.feed()

    def features(self):
        with self.a.db() as c:
            return {r[0]:json.loads(r[1]) for r in c.execute('SELECT track,record FROM audio_features')}

    def import_features(self,records):
        from .traits import validate_record
        catalog={s['id']:s for s in self.a.library()}
        checked=[]
        for record in records:
            song=catalog.get(record.get('track'))
            if not song or song['mtime']!=record.get('mtime') or song['size']!=record.get('size'):
                continue
            checked.append(validate_record(record))
        with self.a.db() as c:
            c.executemany('INSERT INTO audio_features VALUES(?,?,?) ON CONFLICT(track) DO UPDATE SET record=excluded.record,created=excluded.created',
                          [(r['track'],json.dumps(r,ensure_ascii=False),time.time()) for r in checked])
        return len(checked)

    def ensure(self,now=None):
        now=time.time() if now is None else now
        with self.lock:
            current=self.get('currentIds',[])
            if current and now<self.get('nextRefresh',0):
                return
            excluded=self.a.quality.excluded()
            songs=[]
            for song in self.a.library():
                if song['id'] in excluded or song.get('pool') in ('retiring','retired'):
                    continue
                try:self.a.find_track(song['id'])
                except self.a.HTTPException:continue
                songs.append(song)
            if not songs:
                return
            previous={}
            with self.a.db() as c:
                for mid in current:
                    row=c.execute('SELECT kind,group_key,songs FROM mix_editions WHERE id=?',(mid,)).fetchone()
                    if row:previous[(row[0],row[1])]=[s['id'] for s in json.loads(row[2])]
            rng=random.Random(uuid.uuid4().hex)
            artist_groups=defaultdict(list);series_groups=defaultdict(list);style_groups=defaultdict(list)
            descriptions={}
            features=self.features()
            for song in songs:
                artist=re.sub(r'\s+(?:- Topic|Official(?: YouTube Channel)?|OFFICIAL CHANNEL)$','',song['artist'],flags=re.I).strip()
                if artist and artist!='未知歌手':artist_groups[artist].append(song)
                series=series_group(song['album'])
                if series:
                    series_groups[series[0]].append(song);descriptions[series[0]]=series[1]
                evidence=features.get(song['id'],{})
                if evidence.get('mtime')==song['mtime'] and evidence.get('size')==song['size'] and evidence.get('music') is True:
                    for genre in evidence.get('genres',[]):
                        if genre['tag'] in GENRES and genre['confidence']>=.8:
                            style_groups[genre['tag']].append(song)
            choices=[]
            for kind,groups,count in [('artist',artist_groups,3),('style',style_groups,3),('series',series_groups,2)]:
                keys=[k for k,v in groups.items() if len(v)>=3]
                rng.shuffle(keys)
                keys.sort(key=lambda k:(kind,k) in previous)
                for key in keys[:count]:
                    title=GENRES[key] if kind=='style' else key
                    description={'artist':'一位歌手，不同作品','style':'依据音频片段听辨的曲风组合','series':descriptions.get(key,'同系列')}[kind]
                    choices.append((kind,key,title,description,groups[key]))
            if len(songs)>=3:
                choices.extend([('random','wander','随机漫游','不同歌手交错播放',songs),('random','shuffle','随心混合','从音乐库随机组合',songs)])
            elif not choices:
                choices.append(('random','small','随心混合','从现有歌曲开始',songs))
            hours=self.get('intervalHours',24)
            expires=now+hours*3600
            editions=[]
            for kind,key,title,description,candidates in choices:
                candidates=list({s['id']:s for s in candidates}.values())
                rng.shuffle(candidates)
                prior=previous.get((kind,key),[])
                # Prefer different members when enough tracks exist, then change their order.
                candidates.sort(key=lambda s:s['id'] in prior)
                if kind=='random':
                    counts=defaultdict(int);diverse=[]
                    for song in candidates:
                        if counts[song['artist']]<2:
                            diverse.append(song);counts[song['artist']]+=1
                    candidates=diverse or candidates
                selected=candidates[:12]
                if len(selected)>1 and [s['id'] for s in selected]==prior:
                    selected=selected[1:]+selected[:1]
                editions.append((str(uuid.uuid4()),kind,key,title,description,now,expires,json.dumps(selected,ensure_ascii=False)))
            with self.a.db() as c:
                c.execute('BEGIN IMMEDIATE')
                c.executemany('INSERT INTO mix_editions(id,kind,group_key,title,description,created,expires,songs) VALUES(?,?,?,?,?,?,?,?)',editions)
                self.put(c,'currentIds',[e[0] for e in editions])
                self.put(c,'nextRefresh',expires)

    def feed(self):
        self.ensure()
        current=self.get('currentIds',[])
        with self.a.db() as c:
            rows=[dict(r) for r in c.execute('SELECT * FROM mix_editions WHERE saved=1 ORDER BY saved_at DESC')]
            saved_ids={r['id'] for r in rows}
            for mid in current:
                if mid not in saved_ids:
                    row=c.execute('SELECT * FROM mix_editions WHERE id=?',(mid,)).fetchone()
                    if row:rows.append(dict(row))
        catalog={s['id']:s for s in self.a.library()}
        result=[]
        for row in rows:
            snapshots=json.loads(row.pop('songs'))
            packed=[]
            for snapshot in snapshots:
                song=catalog.get(snapshot['id'],snapshot)
                try:self.a.find_track(song['id']);available=True
                except self.a.HTTPException:available=False
                packed.append({**song,'available':available})
            result.append({**row,'tracks':packed,'active':row['id'] in current,
                           'saved':bool(row['saved']),'count':len(packed)})
        return {'mixes':result,'intervalHours':self.get('intervalHours',24),'nextRefresh':self.get('nextRefresh',0),
                'styleAnalyzed':len(self.features())}

    def save(self,mid,saved,updated):
        with self.lock,self.a.db() as c:
            c.execute('BEGIN IMMEDIATE')
            row=c.execute('SELECT * FROM mix_editions WHERE id=?',(mid,)).fetchone()
            if not row:raise self.a.HTTPException(404,'这张歌单不存在，请同步歌单列表')
            updated=min(time.time(),updated)
            if row['saved_at']>updated:
                return {'ok':True,'saved':bool(row['saved'])}
            c.execute('UPDATE mix_editions SET saved=?,saved_at=? WHERE id=?',(saved,updated,mid))
            songs=json.loads(row['songs'])
            if saved:
                c.executemany('INSERT OR IGNORE INTO saved_mix_tracks VALUES(?,?)',[(mid,s['id']) for s in songs])
                for song in songs:
                    self.a.radio.promote(c,song['id'])
                    if not c.execute('SELECT 1 FROM tracks WHERE id=?',(song['id'],)).fetchone():
                        c.execute("UPDATE jobs SET status='queued',error='' WHERE track=? AND origin='pool' AND status NOT IN ('queued','downloading')",(song['id'],))
            else:
                c.execute('DELETE FROM saved_mix_tracks WHERE mix=?',(mid,))
                for song in songs:
                    if self.protected(c,song['id']):continue
                    c.execute("""UPDATE pool_entries SET state='explore' WHERE track=? AND state='kept'
                        AND NOT EXISTS(SELECT 1 FROM listens WHERE track=pool_entries.track)
                        AND NOT EXISTS(SELECT 1 FROM playback_sessions WHERE track=pool_entries.track AND seconds>0)
                        AND NOT EXISTS(SELECT 1 FROM favorites WHERE track=pool_entries.track AND liked=1)
                        AND EXISTS(SELECT 1 FROM jobs WHERE video=pool_entries.video AND origin='pool' AND visible=0)""",(song['id'],))
        return {'ok':True,'saved':saved}
