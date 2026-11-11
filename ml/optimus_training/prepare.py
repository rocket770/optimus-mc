"""Prepare FFA observations; sealed lives can be read while schema-2 logging continues."""
from collections import Counter, defaultdict, deque
from contextlib import closing
from .combat_diagnostics import analyze_combat
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import tempfile

WEAPONS = {'fist', 'sword', 'axe', 'bow', 'other'}
DEATH_REASONS = {'left_pit_or_server_return', 'self_death_pit_return', 'self_dead_observed', 'self_death_chat'}
HORIZON = 20


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def part_key(path):
    match = re.fullmatch(r'(.*)-(\d+)\.jsonl', path.name)
    return (match[1], int(match[2])) if match else (path.name, 0)


def ingest(db, inputs, ignored):
    db.executescript('''
        CREATE TABLE sessions(sid TEXT PRIMARY KEY, schema INT, contract TEXT, outcome_contract TEXT, closed INT DEFAULT 0, dropped INT DEFAULT 0);
        CREATE TABLE lives(sid TEXT, life INT, start INT, end INT, death INT DEFAULT 0,
                           reason TEXT, first_drop INT, last_drop INT, PRIMARY KEY(sid,life));
        CREATE TABLE episodes(sid TEXT, ep INT, life INT, started INT DEFAULT 0, ended INT DEFAULT 0,
                              boundary TEXT, PRIMARY KEY(sid,ep));
        CREATE TABLE frames(sid TEXT, life INT, ep INT, tick INT, body TEXT, PRIMARY KEY(sid,life,ep,tick));
        CREATE TABLE outcomes(sid TEXT, id INT, life INT, tick INT, kind TEXT, ep INT, PRIMARY KEY(sid,id));
        CREATE TABLE combat(sid TEXT, id INT, life INT, ep INT, tick INT, body TEXT, PRIMARY KEY(sid,id));
        CREATE INDEX combat_episode_time ON combat(sid,life,ep,tick);
    ''')
    legacy_life = defaultdict(lambda: 1)
    for path in sorted(map(Path, inputs), key=part_key):
        sid, schema = None, None
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                try:
                    row = json.loads(line)
                except (ValueError, TypeError):
                    ignored['invalid_or_partial_rows'] += 1
                    continue
                if not isinstance(row, dict):
                    ignored['invalid_or_partial_rows'] += 1
                    continue
                kind = row.get('type')
                if kind == 'session':
                    sid, schema = row.get('session'), row.get('schema')
                    if not isinstance(sid, str) or schema not in (1, 2):
                        sid = None
                        ignored['unknown_schema'] += 1
                        continue
                    old = db.execute('SELECT schema FROM sessions WHERE sid=?', (sid,)).fetchone()
                    if old and old[0] != schema:
                        sid = None
                        ignored['conflicting_schema'] += 1
                        continue
                    db.execute('INSERT OR IGNORE INTO sessions(sid,schema,contract,outcome_contract) VALUES(?,?,?,?)',
                               (sid, schema, row.get('feature_contract'), row.get('outcome_contract')))
                    continue
                if sid is None:
                    continue
                if kind == 'session_end':
                    dropped = row.get('dropped', 0)
                    if not integer(dropped):
                        dropped = 1
                    db.execute('UPDATE sessions SET closed=1,dropped=dropped+? WHERE sid=?', (dropped, sid))
                    if schema == 1:
                        db.execute('''UPDATE lives SET end=COALESCE(end,(SELECT MAX(tick) FROM frames WHERE sid=? AND life=?)),
                                      reason=COALESCE(reason,'session_stopped') WHERE sid=? AND life=?''',
                                   (sid, legacy_life[sid], sid, legacy_life[sid]))
                    continue
                tick = row.get('tick')
                life = row.get('life') if schema == 2 else legacy_life[sid]
                if not integer(tick) or not integer(life) or life < 1:
                    ignored['invalid_event'] += 1
                    continue
                if kind == 'combat_outcome' and schema == 2:
                    event_id, outcome = row.get('outcome_id'), row.get('outcome')
                    if integer(event_id) and outcome in ('kill', 'death') and row.get('source') == 'server_kill_feed':
                        ep = row.get('episode') if row.get('matched_target') is True else None
                        db.execute('INSERT OR IGNORE INTO outcomes VALUES(?,?,?,?,?,?)',
                                   (sid, event_id, life, tick, outcome, ep if integer(ep) else None))
                    else:
                        ignored['invalid_outcome'] += 1
                    continue
                if kind in ('life_start', 'life_end') and schema == 2:
                    db.execute('INSERT OR IGNORE INTO lives(sid,life) VALUES(?,?)', (sid, life))
                    dropped = row.get('dropped_total')
                    if not integer(dropped):
                        ignored['missing_loss_watermark'] += 1
                        continue
                    if kind == 'life_start':
                        db.execute('UPDATE lives SET start=?,first_drop=? WHERE sid=? AND life=?', (tick, dropped, sid, life))
                    else:
                        db.execute('UPDATE lives SET end=?,death=?,reason=?,last_drop=? WHERE sid=? AND life=?',
                                   (tick, row.get('death') is True, row.get('reason', 'unknown'), dropped, sid, life))
                    continue
                if kind in ('combat_decision', 'combat_animation') and schema == 2:
                    event_id, ep = row.get('decision_id'), row.get('episode')
                    if integer(event_id) and (integer(ep) or kind == 'combat_animation' and ep is None) and row.get('combat_contract') == 'aim-timing-v1':
                        db.execute('INSERT OR IGNORE INTO combat VALUES(?,?,?,?,?,?)',
                                   (sid, event_id, life, ep, tick, json.dumps(row)))
                    else:
                        ignored['invalid_combat_decision'] += 1
                    continue
                if kind not in ('frame', 'episode_start', 'episode_end'):
                    continue
                ep = row.get('episode')
                if not integer(ep):
                    ignored['invalid_episode'] += 1
                    continue
                if schema == 1:
                    db.execute('INSERT OR IGNORE INTO lives(sid,life,start) VALUES(?,?,?)', (sid, life, tick))
                db.execute('INSERT OR IGNORE INTO episodes(sid,ep,life) VALUES(?,?,?)', (sid, ep, life))
                if kind == 'episode_start':
                    db.execute('UPDATE episodes SET started=1 WHERE sid=? AND ep=?', (sid, ep))
                elif kind == 'episode_end':
                    db.execute('UPDATE episodes SET ended=1,boundary=? WHERE sid=? AND ep=?', (json.dumps(row), sid, ep))
                    if schema == 1 and row.get('reason') in DEATH_REASONS:
                        db.execute('UPDATE lives SET end=?,death=1,reason=? WHERE sid=? AND life=?',
                                   (tick, row['reason'], sid, life))
                        legacy_life[sid] += 1
                else:
                    db.execute('INSERT OR REPLACE INTO frames VALUES(?,?,?,?,?)', (sid, life, ep, tick, json.dumps(row)))
        db.commit()


def valid_frame(row):
    try:
        for person in (row['self'], row['opponent']):
            for key in ('x', 'y', 'z', 'vx', 'vy', 'vz', 'yaw', 'pitch', 'health_observed', 'absorption_observed', 'armor', 'hurt'):
                if not finite(person[key]):
                    return False
            if person['weapon'] not in WEAPONS:
                return False
            if 'head_yaw' in person and not finite(person['head_yaw']):
                return False
            if 'swinging' in person and not isinstance(person['swinging'], bool):
                return False
        action = row['action']
        return (type(action['forward']) is int and action['forward'] in (-1, 0, 1)
                and type(action['side']) is int and action['side'] in (-1, 0, 1)
                and integer(row.get('nearby_other_players', 0)))
    except (KeyError, TypeError):
        return False


def separation(row):
    a, b = row['self'], row['opponent']
    return math.hypot(a['x'] - b['x'], a['z'] - b['z'])


def damage(a, b):
    return max(0., a['health_observed'] + a['absorption_observed'] - b['health_observed'] - b['absorption_observed'])


def activity(window):
    recent = [r for r in window if window[-1]['tick'] - r['tick'] < 20]
    if any(r['opponent'].get('swinging') is True and r.get('opponent_aims_at_self') is True for r in recent):
        return 'active', 'directed_swing', 'observed'
    # Absence of damage is never enough to declare a passive opponent.
    if len(window) < 40 or any(window[i]['tick'] != window[i-1]['tick'] + 1 for i in range(1, len(window))):
        return 'uncertain', 'short_or_gapped_history', 'unknown'
    if not all(isinstance(r['opponent'].get('swinging'), bool) for r in window):
        return 'uncertain', 'legacy_missing_swing_observations', 'unknown'
    if any(r['opponent']['swinging'] for r in window):
        return 'uncertain', 'undirected_swing', 'unknown'
    path = sum(math.hypot(b['opponent']['x']-a['opponent']['x'], b['opponent']['z']-a['opponent']['z'])
               for a, b in zip(window, window[1:]))
    headings = [r['opponent'].get('head_yaw', r['opponent']['yaw']) for r in window]
    turns = sum(abs((b-a+180) % 360-180) for a, b in zip(headings, headings[1:]))
    if path < .25 and turns < 10 and sum(separation(r) <= 3.5 for r in window) >= 30:
        return 'passive', 'stationary_without_swings_2s', 'observed'
    return 'uncertain', 'movement_without_directed_attack', 'unknown'


def outcomes(future, end_tick, died):
    start = future[0]['tick']
    contiguous = all(b['tick'] == a['tick'] + 1 for a, b in zip(future, future[1:]))
    terminal = bool(died and start <= end_tick <= start + HORIZON)
    full = contiguous and future[-1]['tick'] == start + HORIZON
    loss = sum(damage(a['self'], b['self']) for a, b in zip(future, future[1:]) if b['tick'] == a['tick'] + 1)
    return dict(horizon_ticks=HORIZON, complete=full or terminal, damage_complete=full,
                damage_observed=loss, death=terminal,
                contact_fraction=sum(separation(r) <= 3.5 for r in future) / len(future))


def windows(source):
    source = iter(source)
    pending = deque()
    peek = next(source, None)
    while pending or peek is not None:
        if not pending:
            pending.append(peek)
            peek = next(source, None)
        while peek is not None and peek['tick'] <= pending[0]['tick'] + HORIZON:
            pending.append(peek)
            peek = next(source, None)
        yield pending[0], pending[1] if len(pending) > 1 else peek, list(pending)
        pending.popleft()


def validated(rows, ignored):
    previous = None
    for record in rows:
        frame = json.loads(record[0])
        if not valid_frame(frame):
            ignored['invalid_frames'] += 1
        elif frame['tick'] == previous:
            ignored['duplicate_ticks'] += 1
        else:
            previous = frame['tick']
            yield frame


def choose_splits(lives):
    sessions = sorted({r['sid'] for r in lives}, key=lambda s: hashlib.sha256(s.encode()).hexdigest())
    if len(sessions) >= 2:
        validation = set(sessions[:max(1, round(len(sessions)*.2))])
        return {(r['sid'], r['life']): 'validation' if r['sid'] in validation else 'train' for r in lives}, 'whole_session'
    if len(lives) >= 5:
        ordered = sorted(lives, key=lambda r: r['start'])
        cut = max(2, int(len(ordered)*.8))
        splits = {(r['sid'], r['life']): 'train' if i < cut-1 else 'purged' if i == cut-1 else 'validation'
                  for i, r in enumerate(ordered)}
        return splits, 'chronological_lives_with_one_life_gap'
    return {(r['sid'], r['life']): 'train' for r in lives}, 'insufficient_independent_lives'


def prepare(inputs, output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    ignored = Counter()
    cohorts = defaultdict(lambda: Counter(frames=0, transitions=0, damage_observed=0, seconds=0, episodes=0))
    activities, matchups, splits = Counter(), Counter(), Counter()
    activity_cohorts = defaultdict(lambda: Counter(transitions=0, seconds=0., damage_observed=0., death_windows=0))
    episodes = rows = 0
    with tempfile.TemporaryDirectory(prefix='dg-prepare-') as scratch, closing(sqlite3.connect(str(Path(scratch)/'frames.db'))) as db:
        db.row_factory = sqlite3.Row
        ingest(db, inputs, ignored)
        lives = []
        for row in db.execute('SELECT l.*,s.schema,s.contract,s.outcome_contract,s.closed,s.dropped FROM lives l JOIN sessions s USING(sid)'):
            row = dict(row)
            usable = row['end'] is not None and row['start'] is not None and row['end'] >= row['start']
            usable &= bool(row['closed'] and not row['dropped']) if row['schema'] == 1 else (
                row['first_drop'] is not None and row['last_drop'] == row['first_drop'])
            if usable:
                lives.append(row)
            else:
                ignored['open_or_lossy_lives'] += 1
        ignored['open_or_lossy_sessions'] = db.execute('SELECT COUNT(*) FROM sessions WHERE schema=1 AND (closed=0 OR dropped>0)').fetchone()[0]
        ignored['incomplete_episodes'] = db.execute('SELECT COUNT(*) FROM episodes WHERE started=0 OR ended=0').fetchone()[0]
        # Legacy analysis-only sessions must not consume the learning holdout
        # when the user upgrades partway through continuous collection.
        framed_lives = {(r[0], r[1]) for r in db.execute('SELECT DISTINCT sid,life FROM frames')}
        exact_lives = [r for r in lives if r['schema'] == 2 and r['contract'] == 'DGML16-v1'
                       and (r['sid'], r['life']) in framed_lives]
        partition, method = choose_splits(exact_lives or lives)
        with (output/'transitions.jsonl').open('w', encoding='utf-8') as target, (output/'episodes.jsonl').open('w', encoding='utf-8') as episode_file:
            for life in lives:
                sid, number = life['sid'], life['life']
                split = partition.get((sid, number), 'analysis')
                if split == 'purged':
                    ignored['purged_lives'] += 1
                    continue
                boundaries = {}
                kill_ticks = defaultdict(list)
                for credit in db.execute("SELECT tick,ep FROM outcomes WHERE sid=? AND life=? AND kind='kill' AND ep IS NOT NULL", (sid, number)):
                    kill_ticks[credit['ep']].append(credit['tick'])
                seen_weapons = defaultdict(set)
                for ep in db.execute('SELECT ep,boundary FROM episodes WHERE sid=? AND life=? AND started=1 AND ended=1', (sid, number)):
                    boundary = json.loads(ep['boundary'])
                    boundaries[ep['ep']] = boundary
                    episodes += 1
                    episode_file.write(json.dumps(dict(session=sid, life=number, episode=ep['ep'], split=split, boundary=boundary,
                                                       life_death=bool(life['death']), life_end_tick=life['end']), allow_nan=False)+'\n')
                records = db.execute('''SELECT f.body FROM frames f JOIN episodes e ON f.sid=e.sid AND f.ep=e.ep
                                         WHERE f.sid=? AND f.life=? AND e.started=1 AND e.ended=1
                                         AND f.tick BETWEEN ? AND ? ORDER BY f.tick,f.ep''', (sid, number, life['start'], life['end']))
                history = deque(maxlen=40)
                previous_episode = None
                for current, next_row, future_rows in windows(validated(records, ignored)):
                    ep = current['episode']
                    if ep != previous_episode:
                        history.clear()
                    previous_episode = ep
                    history.append(current)
                    if next_row is None or next_row['episode'] != ep:
                        continue
                    if next_row['tick'] != current['tick'] + 1:
                        ignored['observation_gaps'] += 1
                        continue
                    own, enemy = current['self'], current['opponent']
                    observed_damage = damage(own, next_row['self'])
                    cohort = enemy['weapon'] if enemy['weapon'] == next_row['opponent']['weapon'] else 'weapon_changed'
                    nearby = current.get('nearby_other_players', 0)
                    if nearby:
                        cohort = 'crowded_'+cohort
                    label, evidence, confidence = activity(list(history))
                    features = current.get('policy_features')
                    exact = (life['schema'] == 2 and life['contract'] == 'DGML16-v1' and isinstance(features, list)
                             and len(features) == 16 and all(finite(v) and abs(v) <= 10 for v in features))
                    future = outcomes(future_rows, life['end'], life['death'])
                    future['confirmed_kills'] = sum(current['tick'] <= t <= current['tick']+HORIZON for t in kill_ticks[ep])
                    future['kill_feed_available'] = life['outcome_contract'] == 'server-feed-v1'
                    future['terminal_source'] = life['reason'] if future['death'] else None
                    sample = dict(schema=2, session=sid, life=number, episode=ep, split=split, tick=current['tick'],
                                  state={'self': own, 'opponent': enemy}, action=current['action'],
                                  next_state={'self': next_row['self'], 'opponent': next_row['opponent']},
                                  own_damage_observed=observed_damage, opponent_weapon_cohort=cohort,
                                  weapon_matchup=own['weapon']+'_vs_'+enemy['weapon'], nearby_other_players=nearby,
                                  opponent_activity=label, activity_evidence=evidence, activity_confidence=confidence,
                                  policy_features=features if exact else None, features_source='runtime_exact' if exact else 'unavailable',
                                  policy_eligible=exact and current.get('policy_eligible') is True,
                                  future=future, episode_end_reason=boundaries[ep].get('reason', 'unknown'))
                    target.write(json.dumps(sample, separators=(',', ':'), allow_nan=False)+'\n')
                    rows += 1
                    splits[split] += 1
                    activities[label] += 1
                    matchups[sample['weapon_matchup']] += 1
                    group = activity_cohorts[label+'|'+sample['weapon_matchup']+'|'+('crowded' if nearby else 'isolated')]
                    group['transitions'] += 1
                    group['seconds'] += .05
                    group['damage_observed'] += observed_damage
                    group['death_windows'] += future['death']
                    group['confirmed_kill_windows'] += future['confirmed_kills']
                    c = cohorts[cohort]
                    c['frames'] += 1
                    c['transitions'] += 1
                    c['seconds'] += .05
                    c['damage_observed'] += observed_damage
                    seen_weapons[ep].add(cohort)
                for weapons in seen_weapons.values():
                    for weapon in weapons:
                        cohorts[weapon]['episodes'] += 1
        combat_report = analyze_combat(db, lives, partition, output)
        report = dict(schema=2, combat_diagnostics=combat_report, sessions=db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0],
                      complete_lives=len(lives), deaths=sum(bool(r['death']) for r in lives), complete_episodes=episodes,
                      recorded_deaths=db.execute('SELECT COUNT(*) FROM lives WHERE death=1').fetchone()[0],
                      confirmed_kills=db.execute("SELECT COUNT(*) FROM outcomes WHERE kind='kill'").fetchone()[0],
                      confirmed_deaths=db.execute("SELECT COUNT(*) FROM (SELECT DISTINCT sid,life FROM outcomes WHERE kind='death')").fetchone()[0],
                      matched_kills=db.execute("SELECT COUNT(*) FROM outcomes WHERE kind='kill' AND ep IS NOT NULL").fetchone()[0],
                      transitions=rows, splits=dict(splits), holdout_method=method,
                      has_session_holdout=method == 'whole_session' and bool(splits['train'] and splits['validation']),
                      has_independent_life_holdout=method.startswith('chronological') and bool(splits['train'] and splits['validation']),
                      opponent_weapon_cohorts=dict(cohorts), weapon_matchups=dict(matchups), opponent_activity=dict(activities),
                      activity_weapon_cohorts=dict(activity_cohorts), ignored=dict(ignored),
                      model_updated=False, weapon_context_in_existing_runtime_model=False,
                      notes=['Pit return teleports in this configured FFA count as self deaths; attribution to a particular attacker is unknown.',
                             'Legacy return events are counted, but legacy deaths without a selected target may be absent.',
                             'Passive means observed stationary/no swings for two seconds, not proof that an account is AFK.',
                             'Missing swing observations remain uncertain; zero incoming damage alone never establishes passivity.',
                             'Remote damage and nearby-player observations do not establish who landed a hit.',
                             'Only completed loss-free lives are accepted from a running schema-2 session; legacy sessions must be closed.',
                             'Exact runtime features are required by the trainer; older observations remain available for analysis.',
                             'Preparation never changes model weights or game settings.'])
    (output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report