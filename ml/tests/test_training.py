import json
from pathlib import Path
import tempfile
import unittest
from optimus_training.prepare import prepare, activity, windows
from optimus_training.train import train, probabilities, export_model, load_samples
from .fixtures import player, frame


class FFAPreparationTests(unittest.TestCase):
    def root(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        return Path(temp.name)

    def recording(self, root, lives=1, closed=True, loss=0, legacy=False):
        rows = [dict(type='session', schema=1 if legacy else 2, session='run', feature_contract='DGML16-v1')]
        for life in range(1, lives+1):
            start = life*100
            if not legacy:
                rows.append(dict(type='life_start', tick=start, life=life, dropped_total=0))
            rows.append(dict(type='episode_start', tick=start, episode=life, life=life))
            for tick in range(start, start+60):
                row = frame(tick)
                row.update(episode=life, life=life)
                if legacy:
                    row['opponent'].pop('swinging')
                    row.pop('policy_features')
                rows.append(row)
            reason = 'left_pit_or_server_return' if legacy else 'self_death_pit_return'
            rows.append(dict(type='episode_end', tick=start+60, life=life, episode=life, reason=reason))
            if not legacy:
                rows.append(dict(type='life_end', tick=start+60, life=life, death=True, reason=reason, dropped_total=loss))
        if closed:
            rows.append(dict(type='session_end', dropped=loss))
        path = root/'fight-run-0.jsonl'
        path.write_text('\n'.join(json.dumps(r) for r in rows), encoding='utf8')
        return path

    def test_active_requires_swing_evidence_not_incoming_damage(self):
        observed = [frame(i) for i in range(40)]
        self.assertEqual(activity(observed)[0], 'active')
        for row in observed:
            row['opponent']['swinging'] = False
        self.assertEqual(activity(observed)[0], 'passive')
        for row in observed:
            row['opponent'].pop('swinging')
        self.assertEqual(activity(observed)[0], 'uncertain')

    def test_moving_without_swing_is_uncertain(self):
        self.assertEqual(activity([frame(i, False, moving=True) for i in range(40)])[0], 'uncertain')

    def test_swings_away_from_bot_are_uncertain(self):
        rows = [frame(i) for i in range(40)]
        for row in rows:
            row['opponent_aims_at_self'] = False
        self.assertEqual(activity(rows)[0], 'uncertain')

    def test_future_attack_does_not_relabel_past(self):
        root = self.root(); path = self.recording(root)
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        for row in rows:
            if row['type'] == 'frame' and row['tick'] < 150:
                row['opponent']['swinging'] = False
        path.write_text('\n'.join(json.dumps(r) for r in rows))
        prepare([path], root/'out')
        data = [json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        self.assertNotEqual(next(r for r in data if r['tick'] == 130)['opponent_activity'], 'active')
        self.assertEqual(next(r for r in data if r['tick'] == 150)['opponent_activity'], 'active')

    def test_running_session_uses_only_sealed_loss_free_lives(self):
        root = self.root(); path = self.recording(root, closed=False)
        with path.open('a') as f:
            f.write('\n{"type":"frame"')
        result = prepare([path], root/'out')
        self.assertEqual(result['deaths'], 1)
        self.assertEqual(result['transitions'], 59)
        self.assertEqual(result['ignored']['invalid_or_partial_rows'], 1)
        self.assertEqual(result['weapon_matchups'], {'fist_vs_sword': 59})
        data = [json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        self.assertTrue(data[-1]['future']['death'])
        self.assertEqual(data[-1]['future']['terminal_source'], 'self_death_pit_return')
        self.assertEqual(data[-1]['state']['self']['health_observed'], 20)

    def test_dropped_life_is_not_used(self):
        root = self.root(); path = self.recording(root, closed=False, loss=1)
        self.assertEqual(prepare([path], root/'out')['transitions'], 0)

    def test_legacy_returns_count_as_death_but_missing_swings_stay_unknown(self):
        root = self.root(); path = self.recording(root, legacy=True)
        result = prepare([path], root/'out')
        self.assertEqual(result['deaths'], 1)
        self.assertEqual(result['opponent_activity'], {'uncertain': 59})

    def test_continuous_run_has_chronological_life_holdout(self):
        root = self.root(); path = self.recording(root, lives=6, closed=False)
        result = prepare([path], root/'out')
        self.assertTrue(result['has_independent_life_holdout'])
        self.assertEqual(result['ignored']['purged_lives'], 1)
        samples = [json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        training = {r['life'] for r in samples if r['split'] == 'train'}
        validation = {r['life'] for r in samples if r['split'] == 'validation'}
        self.assertFalse(training & validation)
        self.assertLess(max(training)+1, min(validation))
        self.assertEqual(result['deaths'], 6)

    def test_lookahead_buffer_is_bounded(self):
        largest = 0
        for _, _, future in windows(frame(i) for i in range(10000)):
            largest = max(largest, len(future))
        self.assertEqual(largest, 21)

    def test_wrong_feature_contract_is_not_marked_exact(self):
        root = self.root(); path = self.recording(root)
        path.write_text(path.read_text().replace('DGML16-v1', 'other-contract'))
        prepare([path], root/'out')
        samples = [json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        self.assertTrue(all(not r['policy_eligible'] and r['policy_features'] is None for r in samples))

    def test_a_good_life_after_a_lossy_life_is_usable(self):
        root = self.root(); path = self.recording(root, lives=2, loss=1, closed=False)
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        for row in rows:
            if row['type'] == 'life_start' and row['life'] == 2:
                row['dropped_total'] = 1
        path.write_text('\n'.join(json.dumps(r) for r in rows))
        result = prepare([path], root/'out')
        self.assertEqual(result['deaths'], 1)
        self.assertEqual(result['recorded_deaths'], 2)
        self.assertEqual(result['transitions'], 59)

    def test_legacy_parts_are_sorted_numerically(self):
        root = self.root(); path = self.recording(root, lives=2, legacy=True)
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        cut = next(i for i, r in enumerate(rows) if r['type'] == 'episode_start' and r['episode'] == 2)
        first, last = root/'fight-run-2.jsonl', root/'fight-run-10.jsonl'
        first.write_text('\n'.join(json.dumps(r) for r in rows[:cut]))
        last.write_text('\n'.join(json.dumps(r) for r in [rows[0]]+rows[cut:]))
        prepare([last, first], root/'out')
        episodes = [json.loads(s) for s in (root/'out/episodes.jsonl').read_text().splitlines()]
        self.assertEqual({r['episode']: r['life'] for r in episodes}, {1: 1, 2: 2})

    def test_fist_and_sword_exposures_have_activity_breakdown(self):
        root = self.root(); path = self.recording(root)
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        for row in rows:
            if row['type'] == 'frame' and row['tick'] < 130:
                row['opponent']['weapon'] = 'fist'
        path.write_text('\n'.join(json.dumps(r) for r in rows))
        result = prepare([path], root/'out')
        self.assertIn('active|fist_vs_fist|isolated', result['activity_weapon_cohorts'])
        self.assertIn('active|fist_vs_sword|isolated', result['activity_weapon_cohorts'])

    def test_legacy_sessions_do_not_consume_new_data_holdout(self):
        root = self.root(); path = self.recording(root, lives=6, closed=False)
        old_root = self.root(); old = self.recording(old_root, legacy=True)
        old.write_text(old.read_text().replace('"session": "run"', '"session": "legacy"'))
        result = prepare([old, path], root/'out')
        self.assertTrue(result['has_independent_life_holdout'])
        samples = [json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        self.assertEqual({r['split'] for r in samples if r['session'] == 'legacy'}, {'analysis'})

    def test_confirmed_outcomes_are_counted_once_and_matched_to_episode(self):
        root = self.root(); path = self.recording(root)
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        rows[0]['outcome_contract'] = 'server-feed-v1'
        credit = dict(type='combat_outcome', outcome='kill', outcome_id=1, source='server_kill_feed', life=1,
                      episode=1, matched_target=True, tick=125)
        rows += [credit, credit, dict(credit, outcome_id=2, matched_target=False, tick=126),
                 dict(type='combat_outcome', outcome='death', outcome_id=3, source='server_kill_feed', life=1, tick=161)]
        path.write_text('\n'.join(json.dumps(r) for r in rows))
        result = prepare([path], root/'out')
        self.assertEqual((result['confirmed_kills'], result['matched_kills'], result['confirmed_deaths'], result['deaths']), (2, 1, 1, 1))
        samples = [json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        row = next(r for r in samples if r['tick'] == 110)
        self.assertEqual(row['future']['confirmed_kills'], 1)
        self.assertTrue(row['future']['kill_feed_available'])
        self.assertEqual(next(r for r in samples if r['tick'] == 126)['future']['confirmed_kills'], 0)


class TrainingTests(unittest.TestCase):
    def fixture(self, passive=0):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        root = Path(temp.name); prepared = root/'prepared'; prepared.mkdir()
        rows = []
        for role, count in [('train', 1000), ('validation', 400)]:
            for i in range(count):
                row = frame(i*4)
                row.update(schema=2, session=role, split=role, life=1, opponent_activity='active',
                           activity_evidence='directed_swing', features_source='runtime_exact')
                row['policy_features'][0] = i % 2
                row['action']['forward'] = i % 2
                row['future'] = dict(complete=True, damage_observed=0 if i % 2 else 2., death=False, contact_fraction=1.)
                if i % 3:
                    row['opponent']['weapon'] = 'fist'
                row['state'] = dict(self=row['self'], opponent=row['opponent'])
                row['next_state'] = dict(self=row['self'], opponent=row['opponent'])
                rows.append(row)
        for i in range(passive):
            row = json.loads(json.dumps(rows[0])); row['opponent_activity'] = 'passive'
            row['future']['confirmed_kills'] = 50
            rows.append(row)
        (prepared/'transitions.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
        return root, prepared

    def test_candidate_is_compatible_and_keeps_weapon_cohorts(self):
        root, prepared = self.fixture()
        result = train(prepared, root/'out', minimum=40, epochs=6)
        self.assertEqual(result['status'], 'candidate_requires_validation')
        self.assertEqual((root/'out/candidate.dgml').read_text().splitlines()[0], 'DGML2 16 24 9')
        self.assertEqual(len(result['candidate_holdout']), 2)
        self.assertFalse(result['installed'])
        self.assertFalse(result['live_improvement_verified'])

    def test_afk_samples_do_not_change_candidate(self):
        root, prepared = self.fixture()
        train(prepared, root/'first', minimum=40, epochs=3)
        extra, poisoned = self.fixture(passive=1000)
        report = train(poisoned, extra/'second', minimum=40, epochs=3)
        self.assertEqual((root/'first/candidate.dgml').read_bytes(), (extra/'second/candidate.dgml').read_bytes())
        self.assertEqual(report['ignored']['passive_or_uncertain'], 1000)

    def test_no_model_when_data_is_insufficient(self):
        root, prepared = self.fixture()
        result = train(prepared, root/'out')
        self.assertEqual(result['status'], 'insufficient_data')
        self.assertFalse((root/'out/candidate.dgml').exists())

    def test_life_leak_is_rejected(self):
        root, prepared = self.fixture()
        path = prepared/'transitions.jsonl'
        path.write_text(path.read_text().replace('"session": "validation"', '"session": "train"'))
        with self.assertRaisesRegex(ValueError, 'both partitions'):
            train(prepared, root/'out', minimum=40, epochs=1)

    def test_feature_normalization_uses_training_only(self):
        root, prepared = self.fixture()
        path = prepared/'transitions.jsonl'
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        for row in rows:
            if row['split'] == 'validation':
                row['policy_features'][0] = 9.
        path.write_text('\n'.join(json.dumps(r) for r in rows))
        train(prepared, root/'out', minimum=40, epochs=2)
        mean = float((root/'out/candidate.dgml').read_text().splitlines()[1].split()[1])
        self.assertLessEqual(mean, 1.)

    def test_passive_only_data_cannot_produce_candidate(self):
        root, prepared = self.fixture()
        path = prepared/'transitions.jsonl'
        path.write_text(path.read_text().replace('"opponent_activity": "active"', '"opponent_activity": "passive"'))
        self.assertEqual(train(prepared, root/'out', minimum=40, epochs=1)['status'], 'insufficient_data')
        self.assertFalse((root/'out/candidate.dgml').exists())

    def test_export_preserves_linear_scores(self):
        import random
        root, _ = self.fixture(); rng = random.Random(9)
        weights = [[rng.uniform(-.5, .5) for _ in range(16)] for _ in range(9)]
        bias = [rng.uniform(-1, 1) for _ in range(9)]
        export_model(root/'model.dgml', [0.]*16, [1.]*16, (weights, bias))
        lines = (root/'model.dgml').read_text().splitlines()
        head_bias = list(map(float, lines[28].split()[1:]))
        head_weights = [list(map(float, s.split()[1:])) for s in lines[29:]]
        for _ in range(20):
            x = [rng.uniform(-7, 7) for _ in range(16)]
            expected = probabilities(x, weights, bias)
            actual = probabilities([v+10 for v in x]+[0.]*8, head_weights, head_bias)
            for a, b in zip(expected, actual):
                self.assertAlmostEqual(a, b, places=12)

    def test_only_confirmed_kill_feedback_adds_offensive_credit(self):
        root, prepared = self.fixture()
        path = prepared/'transitions.jsonl'
        before = load_samples(path, 'fist', 30000, 770)[0]
        rows = [json.loads(s) for s in path.read_text().splitlines()]
        for row in rows:
            row['future']['confirmed_kills'] = 1
        path.write_text('\n'.join(json.dumps(r) for r in rows))
        after = load_samples(path, 'fist', 30000, 770)[0]
        for role in ('train', 'validation'):
            self.assertEqual(len(before[role]), len(after[role]))
            for first, second in zip(before[role], after[role]):
                self.assertAlmostEqual(second['cost'], first['cost']-.5)


if __name__ == '__main__':
    unittest.main()