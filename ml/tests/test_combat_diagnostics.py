import json
from pathlib import Path
import tempfile
import unittest

from optimus_training.prepare import prepare


def decision(tick, request=False, damage=False):
    person = dict(health_observed=18 if damage else 20, absorption_observed=0, hurt=10 if damage else 0)
    return dict(type='combat_decision', combat_contract='aim-timing-v1', decision_id=tick+1,
                tick=tick, life=1, episode=1, combat_mode='guard', gate='none' if request else 'hit_window',
                action='attack' if request else 'none', attack_requested=request,
                body_distance=2, yaw_error_before=45, pitch_error_before=5,
                yaw_error_after=0, pitch_error_after=0, ping_ms=250,
                ray_intersects_body=True, clear_body_ray=True, self=person.copy(), opponent=person.copy())


class CombatDiagnosticsTests(unittest.TestCase):
    def run_capture(self, decisions, closed=True, loss=0):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        rows = [dict(type='session', schema=2, session='anonymous-run', feature_contract='DGML16-v1'),
                dict(type='life_start', tick=0, life=1, dropped_total=0),
                dict(type='episode_start', tick=0, life=1, episode=1)] + decisions
        if closed:
            rows += [dict(type='episode_end', tick=20, life=1, episode=1, reason='target_lost'),
                     dict(type='life_end', tick=20, life=1, death=False, dropped_total=loss)]
        source = root/'fight-example-0.jsonl'
        source.write_text('\n'.join(json.dumps(row) for row in rows), encoding='utf-8')
        output = root/'prepared'
        report = prepare([source], output)
        reviews = [json.loads(line) for line in (output/'attack-reviews.jsonl').read_text().splitlines()]
        return report['combat_diagnostics'], reviews, output

    def test_delayed_damage_is_observed_not_confirmed(self):
        report, reviews, output = self.run_capture([decision(t, t == 0, t >= 5) for t in range(15)])
        self.assertEqual(report['counts']['attack_requests'], 1)
        self.assertEqual(reviews[0]['first_damage_delay_ticks'], 5)
        self.assertEqual(reviews[0]['status'], 'damage_observed_after_request')
        self.assertIsNone(reviews[0]['confirmed_hit'])
        self.assertEqual(reviews[0]['attribution'], 'unknown')
        self.assertFalse(report['trains_aim_or_attack_timing'])
        self.assertEqual(len((output/'combat-decisions.jsonl').read_text().splitlines()), 15)

    def test_complete_window_without_damage_does_not_assert_a_miss(self):
        _, reviews, _ = self.run_capture([decision(t, t == 0) for t in range(15)])
        self.assertEqual(reviews[0]['observation_window_ticks'], 11)
        self.assertEqual(reviews[0]['status'], 'no_damage_observed_in_window')
        self.assertTrue(reviews[0]['complete_observation'])
        self.assertIsNone(reviews[0]['confirmed_hit'])

    def test_short_window_and_target_switch_are_censored(self):
        rows = [decision(t, t == 0) for t in range(15)]
        for row in rows[3:]:
            row['episode'] = 2
            row['opponent']['health_observed'] = 10
        _, reviews, _ = self.run_capture(rows)
        self.assertEqual(reviews[0]['status'], 'censored')
        self.assertIsNone(reviews[0]['first_damage_delay_ticks'])

    def test_gaps_and_invalid_rows_cannot_complete_a_window(self):
        for invalid in (False, True):
            rows = [decision(t, t == 0) for t in range(12)]
            if invalid:
                rows[4]['yaw_error_after'] = None
            else:
                del rows[4]
            _, reviews, _ = self.run_capture(rows)
            self.assertFalse(reviews[0]['complete_observation'])
            self.assertEqual(reviews[0]['status'], 'censored')

    def test_open_and_lossy_lives_are_withheld(self):
        for closed, loss in ((False, 0), (True, 1)):
            report, reviews, _ = self.run_capture([decision(0, True)], closed, loss)
            self.assertEqual(report['counts'].get('decisions', 0), 0)
            self.assertEqual(reviews, [])

    def test_overlapping_requests_remain_ambiguous(self):
        _, reviews, _ = self.run_capture([decision(t, t in (0, 3), t >= 6) for t in range(18)])
        self.assertEqual(len(reviews), 2)
        self.assertTrue(all(row['overlapping_requests'] and row['confirmed_hit'] is None for row in reviews))

    def test_guard_only_session_does_not_consume_movement_holdout(self):
        from .fixtures import frame
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        rows = [dict(type='session', schema=2, session='guard', feature_contract='DGML16-v1'),
                dict(type='life_start', tick=0, life=1, dropped_total=0), decision(0, True),
                dict(type='life_end', tick=20, life=1, death=False, dropped_total=0),
                dict(type='session', schema=2, session='normal', feature_contract='DGML16-v1')]
        for life in range(1, 7):
            start = life*100
            rows += [dict(type='life_start', tick=start, life=life, dropped_total=0),
                     dict(type='episode_start', tick=start, life=life, episode=life)]
            for tick in range(start, start+30):
                row = frame(tick)
                row.update(life=life, episode=life)
                rows.append(row)
            rows += [dict(type='episode_end', tick=start+30, life=life, episode=life, reason='target_lost'),
                     dict(type='life_end', tick=start+30, life=life, death=False, dropped_total=0)]
        source = root/'fight-mixed-0.jsonl'
        source.write_text('\n'.join(json.dumps(row) for row in rows), encoding='utf-8')
        report = prepare([source], root/'prepared')
        self.assertTrue(report['has_independent_life_holdout'])
        self.assertFalse(report['has_session_holdout'])
        self.assertEqual(report['combat_diagnostics']['counts']['attack_requests'], 1)

    def test_unresolved_guard_swing_does_not_invent_an_opponent(self):
        row = dict(type='combat_animation', combat_contract='aim-timing-v1', decision_id=1, tick=0, life=1,
                   combat_mode='guard', action='air_swing', attack_requested=False, gate='target_unresolved')
        report, reviews, output = self.run_capture([row])
        self.assertEqual(report['counts']['animations_without_target'], 1)
        self.assertEqual(report['counts']['air_swings'], 1)
        self.assertEqual(reviews, [])
        self.assertNotIn('opponent', json.loads((output/'combat-decisions.jsonl').read_text()))

    def test_air_swing_is_not_an_attack_request(self):
        row = decision(0)
        row['action'] = 'air_swing'
        report, reviews, _ = self.run_capture([row, row])
        self.assertEqual(report['counts']['decisions'], 1)
        self.assertEqual(report['counts']['air_swings'], 1)
        self.assertEqual(reviews, [])


if __name__ == '__main__':
    unittest.main()