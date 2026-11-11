import json
from pathlib import Path
import tempfile
import unittest
from optimus_training.prepare import prepare
from .fixtures import player


class PreparationTests(unittest.TestCase):
    def fixture(self,dropped=0,closed=True,gap=False):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);root=Path(temp.name)
        rows=[dict(type='session',schema=1,session='synthetic-session'),dict(type='episode_start',episode=1,tick=1)]
        for tick,weapon,hp in [(1,'fist',20),(2,'fist',19),(3,'sword',15),(4,'sword',12)]:
            if gap and tick==2:continue
            rows.append(dict(type='frame',episode=1,tick=tick,self=player('sword',hp,10),opponent=player(weapon,20,4),
                             action=dict(forward=1,side=0,attack_requested=True)))
        rows.append(dict(type='episode_end',episode=1,tick=5,reason='left_pit_or_server_return'))
        if closed:rows.append(dict(type='session_end',dropped=dropped))
        path=root/'fight-fixture.jsonl';path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        return root,path

    def test_weapon_change_and_armor_are_preserved(self):
        root,path=self.fixture();r=prepare([path],root/'out')
        self.assertEqual(r['transitions'],3)
        self.assertEqual(r['opponent_weapon_cohorts']['fist']['damage_observed'],1)
        self.assertEqual(r['opponent_weapon_cohorts']['sword']['damage_observed'],3)
        self.assertEqual(r['opponent_weapon_cohorts']['weapon_changed']['damage_observed'],4)
        samples=[json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        self.assertTrue(all(s['state']['self']['armor']==10 for s in samples))
        self.assertEqual(len({s['split'] for s in samples}),1)
        self.assertTrue(all(s['episode_end_reason']=='left_pit_or_server_return' for s in samples))
        self.assertFalse(r['model_updated']);self.assertFalse(r['weapon_context_in_existing_runtime_model'])

    def test_lossy_session_is_excluded(self):
        root,path=self.fixture(dropped=1);r=prepare([path],root/'out')
        self.assertEqual(r['transitions'],0)
        self.assertEqual(r['ignored']['open_or_lossy_sessions'],1)

    def test_active_session_waits_until_stopped(self):
        root,path=self.fixture(closed=False);self.assertEqual(prepare([path],root/'out')['transitions'],0)

    def test_missing_ticks_are_not_joined(self):
        root,path=self.fixture(gap=True);r=prepare([path],root/'out')
        self.assertEqual(r['transitions'],1);self.assertEqual(r['ignored']['observation_gaps'],1)

    def test_whole_sessions_stay_separate(self):
        root,path=self.fixture();second=root/'fight-second.jsonl'
        second.write_text(path.read_text().replace('synthetic-session','second-session'))
        report=prepare([path,second],root/'out')
        self.assertTrue(report['has_session_holdout'])
        rows=[json.loads(s) for s in (root/'out/transitions.jsonl').read_text().splitlines()]
        for session in {s['session'] for s in rows}:
            self.assertEqual(len({s['split'] for s in rows if s['session']==session}),1)


if __name__=='__main__':unittest.main()