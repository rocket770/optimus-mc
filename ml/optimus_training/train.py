"""Train an offline, outcome-weighted movement candidate. Never installs a model."""
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import random

ACTIONS = [(0, 0), (1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 0), (-1, 1), (-1, -1)]


def eligible(row, self_weapon):
    if row.get('split') not in ('train', 'validation'):
        return 'unpartitioned'
    if row.get('opponent_activity') != 'active' or row.get('activity_evidence') != 'directed_swing':
        return 'passive_or_uncertain'
    if row.get('policy_eligible') is not True or row.get('features_source') != 'runtime_exact':
        return 'no_exact_runtime_features'
    features = row.get('policy_features')
    if not isinstance(features, list) or len(features) != 16 or not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and abs(v) <= 10 for v in features):
        return 'invalid_features'
    if self_weapon != 'any' and row['state']['self']['weapon'] != self_weapon:
        return 'other_self_weapon'
    if row['state']['self']['weapon'] != row['next_state']['self']['weapon'] or row['state']['opponent']['weapon'] != row['next_state']['opponent']['weapon']:
        return 'weapon_change'
    if row.get('future', {}).get('complete') is not True or not isinstance(row['future'].get('death'), bool):
        return 'censored_future'
    if row['action'].get('jump') or row['action'].get('sneak'):
        return 'outside_movement_hook'
    if (row['action']['forward'], row['action']['side']) not in ACTIONS:
        return 'unknown_action'
    return None


def context(row):
    own, enemy = row['state']['self'], row['state']['opponent']
    return '{}_vs_{}|armor{}_{}|{}|{}'.format(own['weapon'], enemy['weapon'], int(own['armor']//5), int(enemy['armor']//5),
                                        'crowded' if row.get('nearby_other_players', 0) else 'isolated',
                                        'kill_feed' if row['future'].get('kill_feed_available') else 'no_kill_feed')


def load_samples(path, self_weapon, maximum, seed, stride=4):
    pools = {'train': [], 'validation': []}
    counts, ignored, activities = Counter(), Counter(), Counter()
    memberships = {'train': set(), 'validation': set()}
    rng = random.Random(seed)
    with Path(path).open(encoding='utf-8') as stream:
        for line in stream:
            try:
                row = json.loads(line)
                activities[row.get('opponent_activity', 'unknown')] += 1
                reason = eligible(row, self_weapon)
                if reason:
                    ignored[reason] += 1
                    continue
                block = '{}:{}:{}:{}'.format(row['session'], row['life'], row['tick']//stride, seed)
                offset = hashlib.sha256(block.encode()).digest()[0] % stride
                if row['tick'] % stride != offset:
                    ignored['temporal_subsample'] += 1
                    continue
                role = row['split']
                life = (row['session'], row['life'])
                future = row['future']
                loss = future['damage_observed']
                contact = future['contact_fraction']
                kills = future.get('confirmed_kills', 0)
                if not isinstance(loss, (int, float)) or not math.isfinite(loss) or loss < 0 or not isinstance(contact, (int, float)) or not math.isfinite(contact) or not 0 <= contact <= 1:
                    ignored['invalid_outcome'] += 1
                    continue
                if type(kills) is not int or kills < 0:
                    ignored['invalid_outcome'] += 1
                    continue
                # Observed global exposure, not a claim that this target dealt damage.
                cost = min(1., loss/20.) + float(future['death']) + .05*(1-contact) - .5*min(1, kills)
                memberships[role].add(life)
                sample = dict(x=row['policy_features'], y=ACTIONS.index((row['action']['forward'], row['action']['side'])),
                              context=context(row), cost=cost, death=bool(future['death']), damage=loss, contact=contact, kills=kills)
                counts[role] += 1
                pool = pools[role]
                limit = maximum if role == 'train' else max(1000, maximum//3)
                if len(pool) < limit:
                    pool.append(sample)
                else:
                    slot = rng.randrange(counts[role])
                    if slot < limit:
                        pool[slot] = sample
            except (KeyError, TypeError, ValueError, OverflowError):
                ignored['invalid_sample'] += 1
    if memberships['train'] & memberships['validation']:
        raise ValueError('A life appears in both partitions; refusing a leaked validation set.')
    return pools, counts, ignored, activities, memberships


def probabilities(x, weights, bias):
    logits = [bias[a]+sum(w*v for w, v in zip(weights[a], x)) for a in range(9)]
    maximum = max(logits)
    values = [math.exp(z-maximum) for z in logits]
    total = sum(values)
    return [v/total for v in values]


def fit(samples, epochs, seed, weighted, progress=None):
    groups = defaultdict(list)
    for row in samples:
        groups[row['context']].append(row)
    keys = sorted(groups)
    moments = {}
    for key, group in groups.items():
        mean = sum(r['cost'] for r in group)/len(group)
        scale = max(.05, math.sqrt(sum((r['cost']-mean)**2 for r in group)/len(group)))
        moments[key] = mean, scale
    weights = [[0.]*16 for _ in range(9)]
    bias = [0.]*9
    rng = random.Random(seed)
    # Uniform matchup/armour/crowd sampling prevents easy sword/fist cohorts
    # dominating just because the free-running bot encountered more of them.
    for epoch in range(epochs):
        rate = .025/(1+epoch*.08)
        for _ in range(len(samples)):
            key = rng.choice(keys)
            row = rng.choice(groups[key])
            mean, scale = moments[key]
            advantage = math.exp(max(-1., min(1., (mean-row['cost'])/scale))) if weighted else 1.
            probs = probabilities(row['z'], weights, bias)
            for action in range(9):
                error = advantage*(probs[action]-(action == row['y']))
                bias[action] -= rate*error
                for feature in range(16):
                    weights[action][feature] -= rate*(error*row['z'][feature]+.002*weights[action][feature])
        if progress and ((epoch+1) % 5 == 0 or epoch+1 == epochs):
            print('{} {}/{}'.format(progress, epoch+1, epochs), flush=True)
    return weights, bias


def evaluate(samples, model, threshold=.35):
    weights, bias = model
    cohorts = defaultdict(lambda: dict(samples=0, loss=0., matches=0, confident=0, damage=0., terminal_windows=0, contact=0., kill_windows=0))
    for row in samples:
        probs = probabilities(row['z'], weights, bias)
        predicted = max(range(9), key=lambda a: probs[a])
        c = cohorts[row['context']]
        c['samples'] += 1
        c['loss'] -= math.log(max(1e-12, probs[row['y']]))
        c['matches'] += predicted == row['y']
        c['confident'] += probs[predicted] >= threshold and max(map(abs, row['z'])) <= 7
        c['damage'] += row['damage']
        c['terminal_windows'] += row['death']
        c['contact'] += row['contact']
        c['kill_windows'] += row['kills']
    result = {}
    for key, c in cohorts.items():
        n = c['samples']
        result[key] = dict(samples=n, action_log_loss=c['loss']/n, action_agreement=c['matches']/n,
                           runtime_confident_fraction=c['confident']/n, observed_damage_per_window=c['damage']/n,
                           observed_death_window_fraction=c['terminal_windows']/n, observed_contact_fraction=c['contact']/n,
                           observed_confirmed_kill_windows=c['kill_windows'])
    return result


def export_model(path, means, scales, model):
    weights, bias = model
    # Embed the linear candidate in the existing 24-ReLU DGML2 contract.
    # z is clipped to [-10,10], so ReLU(z+10)-10 equals z. This retains
    # the Java DGML2 out-of-distribution abstention without a runtime change.
    fmt = lambda values: ' '.join(format(v, '.17g') for v in values)
    rows = ['DGML2 16 24 9', 'MEAN '+fmt(means), 'STD '+fmt(scales), 'B1 '+fmt([10.]*16+[0.]*8)]
    for i in range(24):
        rows.append('L'+str(i)+' '+fmt([float(i == f) for f in range(16)]))
    rows.append('B2 '+fmt([bias[a]-10*sum(weights[a]) for a in range(9)]))
    rows.extend('W'+str(a)+' '+fmt(weights[a]+[0.]*8) for a in range(9))
    Path(path).write_text('\n'.join(rows), encoding='utf-8')


def train(prepared, output, self_weapon='fist', maximum=30000, epochs=25, seed=770, minimum=500, progress=False):
    prepared, output = Path(prepared), Path(output)
    output.mkdir(parents=True, exist_ok=False)
    pools, counts, ignored, activities, memberships = load_samples(prepared/'transitions.jsonl', self_weapon, maximum, seed)
    train_rows, validation = pools['train'], pools['validation']
    report = dict(method='outcome_weighted_behavior_cloning', self_weapon=self_weapon, source=str(prepared),
                  minimum_training_samples=minimum, minimum_validation_samples=max(20, minimum//5),
                  samples_seen=dict(counts), samples_used={k: len(v) for k, v in pools.items()},
                  lives={k: len(v) for k, v in memberships.items()}, ignored=dict(ignored), activity_counts=dict(activities),
                  installed=False, live_improvement_verified=False,
                  notes=['Passive and uncertain periods are excluded from fitting and primary evaluation.',
                         'Costs use observed own damage, known self deaths, matched server-confirmed kills and a small loss-of-contact cost; no dealt-hit rewards are invented.',
                         'Sword/fist, armour and crowd contexts are balanced and normalized separately in fitting.',
                         'The existing runtime has no weapon input; this candidate learns a shared policy across the recorded contexts.',
                         'This is correlational outcome-weighted cloning, not counterfactual reinforcement learning or proof of better PvP.',
                         'Held-out metrics measure action agreement and coverage; recorded outcomes are not predicted policy outcomes.',
                         'A candidate is never installed automatically. Validate in shadow mode and controlled live play before promotion.'])
    reason = None
    if len(train_rows) < minimum or len(validation) < max(20, minimum//5):
        reason = 'More active, complete, exact-feature data is needed in both independent partitions.'
    elif len({r['y'] for r in train_rows}) < 2:
        reason = 'Insufficient observed action diversity; refusing a constant-action candidate.'
    if reason:
        report.update(status='insufficient_data', reason=reason)
        (output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        return report
    means = [sum(r['x'][f] for r in train_rows)/len(train_rows) for f in range(16)]
    scales = [max(.1, math.sqrt(sum((r['x'][f]-means[f])**2 for r in train_rows)/len(train_rows))) for f in range(16)]
    for pool in pools.values():
        for row in pool:
            row['z'] = [max(-10., min(10., (x-means[f])/scales[f])) for f, x in enumerate(row['x'])]
    baseline = fit(train_rows, epochs, seed, False, 'Baseline' if progress else None)
    candidate = fit(train_rows, epochs, seed, True, 'Candidate' if progress else None)
    export_model(output/'candidate.dgml', means, scales, candidate)
    report.update(status='candidate_requires_validation', feature_contract='DGML16-v1', seed=seed, epochs=epochs,
                  training_context_samples=dict(Counter(r['context'] for r in train_rows)),
                  baseline_holdout=evaluate(validation, baseline), candidate_holdout=evaluate(validation, candidate),
                  candidate_file='candidate.dgml')
    (output/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report