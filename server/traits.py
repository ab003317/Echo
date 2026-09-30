"""Title-blind audio excerpts for playlist styles; never infer genre from file names."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import os
import re
import subprocess
import time

from .ai import AIClient, AIConfig, RateLimited
from .settings import local_now
from .mixes import GENRES

TAGS={'vocals','instrumental','distorted_guitar','acoustic_guitar','piano','strings','synth','drum_kit',
      'electronic_drums','bass','steady_pulse','syncopation','busy_rhythm','sparse_arrangement','dense_arrangement',
      'strong_section_contrast','choir','spoken_voice','slow_pulse','moderate_pulse','fast_pulse'}


def validate_record(record):
    seconds=float(record.get('sampledSeconds',0))
    segments=record.get('segments',[])
    if not math.isfinite(seconds) or not 0<seconds<=60.1 or not 1<=len(segments)<=3:
        raise ValueError('Invalid sample coverage')
    if not re.fullmatch('[a-f0-9]{64}',record.get('inputAudioSha256','')):
        raise ValueError('Missing audio fingerprint')
    if any(len(s)!=2 or any(not isinstance(x,(int,float)) or not math.isfinite(x) or x<0 for x in s) for s in segments):
        raise ValueError('Invalid sample offsets')
    if any(s[1]<=0 for s in segments) or abs(sum(s[1] for s in segments)-seconds)>1:
        raise ValueError('Sample duration does not match excerpts')
    checked={**record}
    for field,allowed in [('traits',TAGS),('genres',set(GENRES))]:
        values={}
        for feature in record.get(field,[]):
            confidence=float(feature.get('confidence',0));at=float(feature.get('atSecond',-1))
            tag=feature.get('tag')
            if tag in allowed and .8<=confidence<=1 and 0<=at<=seconds+len(segments):
                values[tag]={'tag':tag,'confidence':confidence,'atSecond':at}
        checked[field]=list(values.values()) if record.get('music') is True else []
    if {'vocals','instrumental'}<=set(t['tag'] for t in checked['traits']):
        raise ValueError('Contradictory vocal classification')
    return checked


class AudioTraits:
    def __init__(self,app):
        self.a=app
        self.slot=0

    def candidates(self,limit=3):
        known=self.a.mixes.features()
        excluded=self.a.quality.excluded()
        songs=[s for s in self.a.library() if s['id'] not in excluded and s.get('pool') not in ('retiring','retired') and s['duration']>0
               and (known.get(s['id'],{}).get('mtime')!=s['mtime'] or known.get(s['id'],{}).get('size')!=s['size'])]
        counts={}
        for record in known.values():
            artist=record.get('creditedArtist','')
            counts[artist]=counts.get(artist,0)+1
        chosen=[]
        while songs and len(chosen)<limit:
            songs.sort(key=lambda s:(counts.get(s['artist'],0),-s.get('favorite',0),-s.get('plays',0),-s['added']))
            song=songs.pop(0);chosen.append(song)
            counts[song['artist']]=counts.get(song['artist'],0)+1
        return chosen

    def prepare(self,song):
        _,path=self.a.find_track(song['id'])
        before=path.stat()
        duration=float(song['duration'])
        if duration<=0:raise ValueError('Missing duration')
        segments=[(0,duration)] if duration<=60 else [(min(duration-20,duration*f),20) for f in (.15,.48,.78)]
        pcm=[]
        for start,length in segments:
            part=subprocess.run(['ffmpeg','-v','error','-ss',str(start),'-i',str(path),'-t',str(length),
                '-vn','-ac','1','-ar','24000','-f','s16le','pipe:1'],capture_output=True,check=True,timeout=45).stdout
            pcm.append(part)
        audio=subprocess.run(['ffmpeg','-v','error','-f','s16le','-ar','24000','-ac','1','-i','pipe:0',
            '-map_metadata','-1','-b:a','64k','-f','mp3','pipe:1'],input=b'\0'*48000+(b'\0'*48000).join(pcm),
            capture_output=True,check=True,timeout=45).stdout
        after=path.stat()
        if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns) or len(audio)<1000:
            raise ValueError('Audio changed during sampling')
        return {'track':song['id'],'mtime':song['mtime'],'size':song['size'],'creditedArtist':song['artist'],
                'segments':segments,'sampledSeconds':sum(len(p) for p in pcm)/48000,
                'inputAudioSha256':hashlib.sha256(audio).hexdigest(),'audio':audio}

    def analyze(self,songs):
        if not songs:return []
        if time.time()<self.a.mixes.get('audioRetryAt',0):
            raise RuntimeError('音频听辨正在等待重试，已有歌单仍可使用')
        with ThreadPoolExecutor(max_workers=3) as workers:
            samples=list(workers.map(self.prepare,songs))
        expected={f'S{i:02}':sample for i,sample in enumerate(samples)}
        feature=lambda tags:{'type':'array','items':{'type':'object','properties':{
            'tag':{'type':'string','enum':sorted(tags)},'confidence':{'type':'number'},'atSecond':{'type':'number'}},
            'required':['tag','confidence','atSecond']}}
        schema={'type':'object','properties':{'audioRead':{'type':'boolean'},'tracks':{'type':'array',
            'minItems':len(samples),'maxItems':len(samples),'items':{'type':'object','properties':{
                'id':{'type':'string','enum':list(expected)},'music':{'type':'boolean'},
                'traits':feature(TAGS),'genres':feature(GENRES)},'required':['id','music','traits','genres']}}},
            'required':['audioRead','tracks']}
        prompt=('Listen to the anonymous audio attachments. No titles, artists, albums, covers or lyric text are provided. '
            'Each attachment joins three 20-second excerpts (or a short full track) with one-second silences. '
            'Report only audible timbre/rhythm/arrangement traits and broad music styles supported by the SOUND. '
            'Do not identify the artist or song; do not derive style from recognized words, language or artist reputation. '
            'Genres are hypotheses about these excerpts, not proof of the whole track. Leave uncertain lists empty. '
            'For each trait/style give confidence 0..1 and a supporting timestamp in the attached sample. '
            'Instrumental means no vocals in the excerpts; never return both vocals and instrumental. '
            'If audio is inaccessible set audioRead=false. Return each ID exactly once: '+', '.join(expected))
        attachments = [(label, base64.b64encode(sample['audio']).decode()) for label, sample in expected.items()]
        try:
            result, model = AIClient(audio=True).generate(prompt, schema, attachments)
        except RateLimited as error:
            with self.a.db() as c:
                self.a.mixes.put(c, 'audioRetryAt', error.retry_at)
            raise
        if result.get('audioRead') is not True or len(result['tracks'])!=len(samples) or {r['id'] for r in result['tracks']}!=set(expected):
            raise RuntimeError('音频听辨结果无法对应原曲')
        records=[]
        for row in result['tracks']:
            sample=expected[row['id']]
            records.append(validate_record({**{k:v for k,v in sample.items() if k!='audio'},
                'music':row['music'],'traits':row['traits'],'genres':row['genres'],'model':model,'source':'audio_excerpts'}))
        self.a.mixes.import_features(records)
        return records

    def loop(self,stop):
        while not stop.wait(30):
            try:
                self.a.mixes.ensure()
                if (not AIConfig.from_env(audio=True).enabled or not self.a.radio.config()['aiEnabled'] or time.time()<self.a.mixes.get('audioRetryAt',0)
                        or time.time()<self.a.mixes.get('audioBootstrapUntil',0)):continue
                day_start=local_now().replace(hour=0,minute=0,second=0,microsecond=0).timestamp()
                with self.a.db() as c:
                    done=c.execute('SELECT COUNT(*) FROM audio_features WHERE created>=?',(day_start,)).fetchone()[0]
                if done<12:
                    self.analyze(self.candidates(min(3,12-done)))
            except Exception as error:
                retry_at=max(self.a.mixes.get('audioRetryAt',0),time.time()+300)
                with self.a.db() as c:
                    self.a.mixes.put(c,'audioRetryAt',retry_at)
                    self.a.mixes.put(c,'audioLastError',{'kind':type(error).__name__,'at':time.time()})
            if stop.wait(300):break
