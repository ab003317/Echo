"""Playback history derived from real sessions, including offline and legacy events."""
import base64
import json
import math


class History:
    def __init__(self, app):
        self.a = app

    def initialize(self):
        with self.a.db() as c:
            c.execute('CREATE TABLE IF NOT EXISTS history_songs(track TEXT PRIMARY KEY,song TEXT NOT NULL)')
            rows = c.execute('SELECT * FROM tracks WHERE id IN (SELECT track FROM listens UNION SELECT track FROM playback_sessions)').fetchall()
            for row in rows:
                self.remember(c, row)

    def remember(self, c, row):
        song = {k:row[k] for k in ('id','title','artist','album','duration','size','mtime','cover')}
        c.execute('INSERT INTO history_songs VALUES(?,?) ON CONFLICT(track) DO UPDATE SET song=excluded.song',
                  (row['id'],json.dumps(song,ensure_ascii=False)))

    def page(self, cursor='', limit=50, query=''):
        boundary = (float('inf'), '\uffff')
        if cursor:
            try:
                raw = json.loads(base64.urlsafe_b64decode(cursor + '='*(-len(cursor)%4)))
                boundary = (float(raw[0]), str(raw[1]))
                if len(raw)!=2 or not math.isfinite(boundary[0]) or len(boundary[1])>100:
                    raise ValueError()
            except Exception:
                raise self.a.HTTPException(400,'播放记录位置无效，请下拉刷新') from None
        with self.a.db() as c:
            rows = c.execute('''WITH events AS (
                SELECT event,track,seconds,started,at,outcome,seq,'session' source FROM playback_sessions WHERE seconds>0
                UNION ALL
                SELECT l.event,l.track,l.seconds,l.at,l.at,'legacy',0,'legacy' FROM listens l
                WHERE l.seconds>0 AND NOT EXISTS(SELECT 1 FROM playback_sessions p WHERE p.event=l.event)
            ) SELECT e.*,t.id availableTrack,t.title,t.artist,h.song snapshot,p.metadata poolMetadata
                FROM events e LEFT JOIN tracks t ON t.id=e.track LEFT JOIN history_songs h ON h.track=e.track
                LEFT JOIN pool_entries p ON p.track=e.track
                WHERE (e.started<? OR (e.started=? AND e.event<?))
                AND (?='' OR INSTR(LOWER(COALESCE(t.title,'')||' '||COALESCE(t.artist,'')||' '||COALESCE(h.song,'')),LOWER(?))>0)
                ORDER BY e.started DESC,e.event DESC LIMIT ?''',
                (boundary[0],boundary[0],boundary[1],query,query,limit+1)).fetchall()
        catalog = {s['id']:s for s in self.a.library()}
        result=[]
        for row in rows[:limit]:
            snapshot=json.loads(row['snapshot'] or '{}')
            fallback=json.loads(row['poolMetadata'] or '{}')
            song=catalog.get(row['track']) or snapshot or {
                'id':row['track'],'title':fallback.get('title','已移除的歌曲'),
                'artist':fallback.get('artist',''),'album':'','duration':fallback.get('duration',0),
                'size':0,'mtime':0,'cover':0}
            try:
                self.a.find_track(row['track'])
                available=True
            except self.a.HTTPException:
                available=False
            result.append({k:row[k] for k in ('event','track','seconds','started','at','outcome','seq','source')} |
                          {'song':song,'available':available})
        more=len(rows)>limit
        next_cursor=base64.urlsafe_b64encode(json.dumps([rows[limit-1]['started'],rows[limit-1]['event']]).encode()).decode().rstrip('=') if more else None
        return {'entries':result,'nextCursor':next_cursor,'hasMore':more}
