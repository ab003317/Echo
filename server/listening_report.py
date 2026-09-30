"""Inspectable facts from the exact evidence snapshot used by the daily analysis.

No generated prose or title-based musical inference enters this contract.
"""
from collections import Counter

AUDIO_TAGS = {'vocals', 'instrumental', 'distorted_guitar', 'acoustic_guitar', 'piano',
             'strings', 'synth', 'drum_kit', 'electronic_drums', 'bass', 'steady_pulse',
             'syncopation', 'busy_rhythm', 'sparse_arrangement', 'dense_arrangement',
             'strong_section_contrast', 'choir', 'spoken_voice', 'slow_pulse',
             'moderate_pulse', 'fast_pulse'}


def period_metrics(rows, complete=False):
    return {'tracks': len(rows), 'sessions': sum(r.get('sessions', 0) for r in rows),
            'seconds': round(sum(r.get('seconds', 0) for r in rows)),
            **{key: sum(r.get(key, 0) for r in rows) for key in ('effectivePlays', 'completed', 'earlySkips')},
            'limited': not complete and len(rows) >= 70}


def build_report(evidence, profile):
    periods = {p: evidence.get('totals', {}).get(p) or period_metrics(evidence.get(p, []))
               for p in ('today', 'recent')}
    signals, seen = [], set()
    for period in ('today', 'recent'):
        rows = sorted(evidence.get(period, []), key=lambda r: (
            r.get('effectivePlays', 0) >= 2 or r.get('earlySkips', 0) >= 2,
            r.get('seconds', 0)), reverse=True)
        for row in rows:
            if row['track'] in seen or len(signals) >= (3 if period == 'today' else 5):
                continue
            if row.get('seconds', 0) <= 0 and row.get('earlySkips', 0) == 0:
                continue
            plays, skips = row.get('effectivePlays', 0), row.get('earlySkips', 0)
            kind = ('mixed' if plays and skips else 'early_skip' if skips else
                    'repeated' if plays >= 2 else 'completed' if row.get('completed', 0) else 'listened')
            signals.append({**{k: row.get(k, '' if k in ('track', 'title', 'artist') else 0)
                              for k in ('track', 'title', 'artist', 'seconds', 'effectivePlays', 'completed', 'earlySkips')},
                            'period': period, 'kind': kind})
            seen.add(row['track'])

    changes = []
    for weight in sorted(profile.get('artists', []), key=lambda r: abs(r['weight']), reverse=True):
        artist, value = weight['artist'], weight['weight']
        if not value:
            continue
        def matching(period):
            return [r for r in evidence.get(period, []) if r.get('artist', '').casefold() == artist.casefold()]
        # today is contained in recent: never add them together.
        recent = matching('recent')
        plays = sum(r.get('effectivePlays', 0) for r in recent)
        skips = sum(r.get('earlySkips', 0) for r in recent)
        favorites = len(matching('favorites'))
        legacy = sum(r.get('legacyPlays', 0) for r in matching('legacy'))
        seed = next((r for r in evidence.get('seedArtists', []) if r['artist'].casefold() == artist.casefold()), None)
        if value < 0:
            if skips < 2:
                continue
            basis, count = 'early_skip', skips
        elif favorites:
            basis, count = 'favorite', favorites
        elif plays:
            basis, count = 'qualified', plays
        elif legacy:
            basis, count = 'legacy', legacy
        elif seed:
            basis, count = 'reference', 0
        else:
            continue
        changes.append({'artist': artist, 'direction': 'less' if value < 0 else 'more',
                        'basis': basis, 'count': count})
        if len(changes) == 6:
            break

    audio = {r['track']: r for r in evidence.get('audioSamples', [])
             if r.get('source') == 'model_inference_from_audio_excerpts' and r.get('sampledSeconds', 0) > 0}
    features = Counter()
    for row in audio.values():
        features.update({t['tag'] for t in row.get('traits', [])
                         if t.get('tag') in AUDIO_TAGS and .8 <= t.get('confidence', 0) <= 1
                         and 0 <= t.get('atSecond', -1) <= row['sampledSeconds'] + len(row.get('segments', []))})
    return {'version': 1, 'day': evidence.get('day', ''), 'timezone': evidence.get('timezone', 'Asia/Hong_Kong'),
            'periods': periods, 'signals': signals, 'changes': changes,
            'sources': {'references': len(evidence.get('external', [])),
                        'favorites': len(evidence.get('favorites', [])),
                        'legacyPlays': sum(r.get('legacyPlays', 0) for r in evidence.get('legacy', [])),
                        'audioTracks': len(audio),
                        'audioSeconds': round(sum(r['sampledSeconds'] for r in audio.values()))},
            'audioFeatures': [{'tag': tag, 'tracks': count} for tag, count in features.most_common(4)],
            'searchDirections': len(profile.get('queries', []))}
