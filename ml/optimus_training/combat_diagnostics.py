"""Describe aim, request gates and delayed observations without inventing hit labels."""
from collections import Counter
import json
import math


def valid(row):
    try:
        for key in ('body_distance', 'yaw_error_before', 'pitch_error_before',
                    'yaw_error_after', 'pitch_error_after', 'ping_ms'):
            if type(row[key]) not in (int, float) or not math.isfinite(row[key]):
                return False
        for key in ('attack_requested', 'ray_intersects_body', 'clear_body_ray'):
            if type(row[key]) is not bool:
                return False
        for person in ('self', 'opponent'):
            for key in ('health_observed', 'absorption_observed', 'hurt'):
                if type(row[person][key]) not in (int, float) or not math.isfinite(row[person][key]):
                    return False
        return (row['combat_mode'] in ('guard', 'normal') and row['action'] in ('none', 'attack', 'air_swing')
                and isinstance(row['gate'], str) and row['ping_ms'] >= 0
                and (not row['attack_requested'] or row['action'] == 'attack'))
    except (KeyError, TypeError):
        return False


def analyze_combat(db, lives, partition, output):
    counts, gates, modes, reviews = Counter(), Counter(), Counter(), Counter()
    sums = Counter()
    files = {'decisions': 'combat-decisions.jsonl', 'requests': 'attack-reviews.jsonl'}
    with (output/files['decisions']).open('w', encoding='utf-8') as decisions, (output/files['requests']).open('w', encoding='utf-8') as requests:
        for life in lives:
            sid, number = life['sid'], life['life']
            split = partition.get((sid, number), 'analysis')
            rows = db.execute('SELECT * FROM combat WHERE sid=? AND life=? AND tick BETWEEN ? AND ? ORDER BY tick,id',
                              (sid, number, life['start'], life['end']))
            for record in rows:
                row = json.loads(record['body'])
                if row.get('type') == 'combat_animation':
                    if row.get('action') != 'air_swing' or row.get('attack_requested') is not False or row.get('combat_mode') not in ('guard', 'normal'):
                        counts['invalid_decisions'] += 1
                        continue
                    row.update(session=sid, split=split)
                    decisions.write(json.dumps(row, separators=(',', ':'), allow_nan=False)+'\n')
                    counts['animations_without_target'] += 1
                    counts['air_swings'] += 1
                    modes[row['combat_mode']] += 1
                    continue
                if not valid(row):
                    counts['invalid_decisions'] += 1
                    continue
                row.update(session=sid, split=split)
                decisions.write(json.dumps(row, separators=(',', ':'), allow_nan=False)+'\n')
                counts['decisions'] += 1
                gates[row['gate']] += 1
                modes[row['combat_mode']] += 1
                counts['air_swings'] += row['action'] == 'air_swing'
                counts['clear_body_ray'] += row['clear_body_ray']
                sums['absolute_yaw_error_after'] += abs(row['yaw_error_after'])
                sums['absolute_pitch_error_after'] += abs(row['pitch_error_after'])
                if not row['attack_requested']:
                    continue
                counts['attack_requests'] += 1
                # A diagnostic observation window, not a claim about server immunity
                # or a successful hit. Client observations arrive after network delay.
                horizon = max(6, min(40, math.ceil(row['ping_ms']/50)+6))
                tick, ep = record['tick'], record['ep']
                previous = row['opponent']
                first_damage = None
                last_tick = tick
                observed_ticks = set()
                future = db.execute('''SELECT tick,body FROM combat WHERE sid=? AND life=? AND ep=?
                                       AND tick>? AND tick<=? ORDER BY tick,id''',
                                    (sid, number, ep, tick, tick+horizon))
                for observation in future:
                    next_row = json.loads(observation['body'])
                    if not valid(next_row):
                        continue
                    current = next_row['opponent']
                    loss = previous['health_observed']+previous['absorption_observed']-current['health_observed']-current['absorption_observed']
                    if first_damage is None and (loss > .01 or current['hurt'] > previous['hurt']):
                        first_damage = observation['tick']-tick
                    previous, last_tick = current, observation['tick']
                    observed_ticks.add(last_tick)
                # A late endpoint alone cannot establish continuous observation.
                complete = last_tick >= tick+horizon and len(observed_ticks) == horizon
                status = ('damage_observed_after_request' if first_damage is not None else
                          'no_damage_observed_in_window' if complete else 'censored')
                overlapping = db.execute('''SELECT body FROM combat WHERE sid=? AND life=? AND ep=?
                                            AND tick BETWEEN ? AND ? AND id<>?''',
                                         (sid, number, ep, tick-horizon, tick+horizon, record['id']))
                overlaps = any(json.loads(other[0]).get('attack_requested') is True for other in overlapping)
                review = dict(session=sid, life=number, episode=ep, decision_id=record['id'], split=split,
                              tick=tick, combat_mode=row['combat_mode'], observation_window_ticks=horizon,
                              complete_observation=complete, status=status, first_damage_delay_ticks=first_damage,
                              overlapping_requests=overlaps, nearby_other_players=row.get('nearby_other_players'),
                              attribution='unknown', confirmed_hit=None)
                requests.write(json.dumps(review, separators=(',', ':'), allow_nan=False)+'\n')
                reviews[status] += 1
                counts['requests_with_overlapping_windows'] += overlaps
    n = counts['decisions']
    report = dict(contract='aim-timing-v1', counts=dict(counts), gates=dict(gates), modes=dict(modes),
                  request_observations=dict(reviews),
                  mean_absolute_aim_error_degrees={key: value/n for key, value in sums.items()} if n else {},
                  files=files, trains_aim_or_attack_timing=False,
                  notes=['Only completed loss-free lives are exported; current lives are withheld.',
                         'Repeated guard decisions in one tick are retained, not independent training samples.',
                         'Damage after a request is an observation, not proof the bot caused it; no confirmed hit labels are generated.',
                         'No observed damage does not establish a miss. Gaps and incomplete windows are censored.',
                         'These diagnostics do not change aim, reach, swing pacing or model weights.'])
    (output/'combat-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report