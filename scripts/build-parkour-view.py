"""Build an offline, self-contained view from the exact fixture and replay CSVs."""
import csv
import gzip
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
labels = {
    'west_quartz_climb': 'West quartz · one-block climb',
    'west_diagonal_gap': 'West corner · diagonal outward',
    'south_slab_climb': 'South slabs · raised landing',
    'wool_diagonal_climb': 'Wool platforms · long diagonal',
    'wool_diagonal_step': 'Wool platforms · reproduced stall',
    'west_quartz_approach': 'West quartz · walking approach + climb',
    'west_diagonal_return': 'West corner · diagonal return',
    'south_slab_parallel': 'South slabs · parallel raised landing',
    'west_slab_walk': 'West slabs · walking transition',
    'west_slab_long_walk': 'West slabs · longer walk',
    'quartz_stair_chain': 'Quartz staircase · continuous ascent',
    'floor_to_quartz_stairs': 'Pit floor → quartz staircase',
    'wool_facing_east': 'Wool stall · initial yaw 90°',
    'wool_facing_south': 'Wool stall · initial yaw 180°',
    'wool_facing_west': 'Wool stall · initial yaw 270°',
}
with (root/'diagnostics/captured-navigation-summary.csv').open(encoding='utf-8-sig') as stream:
    summary={row['case']:row for row in csv.DictReader(stream)}
goals={name:[float(row[k]) for k in ('to_x','to_y','to_z')] for name,row in summary.items()}

def traces(path):
    result = {}
    with path.open(encoding='utf-8-sig') as stream:
        for row in csv.DictReader(stream):
            result.setdefault(row['case'], []).append([
                *[round(float(row[k]),4) for k in ('x','y','z')], row['on_ground']=='true',
                *[row[k]=='true' for k in ('forward','back','jump','sprint')], row['activity']])
    return result

after = traces(root/'diagnostics/captured-navigation.csv')
baseline = root/'diagnostics/captured-navigation-before.csv'
before = traces(baseline) if baseline.exists() else {}
boxes, tops = [], {}
with gzip.open(root/'src/test/resources/dev/optimus/core/duels-collision.tsv.gz', 'rt') as stream:
    for line in stream:
        p = line.split()
        if p[0] != 'B':
            continue
        x, z, top = int(p[1]), int(p[3]), float(p[9])
        if not (-25 <= x < 25 and -127 <= z < -77):
            continue
        boxes.append([int(p[4]), *map(float,p[5:11])])
        if 62.5 <= top <= 74:
            tops[x,z] = max(top,tops.get((x,z),62.5))

cases = []
for name, trace in after.items():
    points = [trace[0][:3],goals[name]] + [r[:3] for r in trace] + [r[:3] for r in before.get(name,[])]
    lo = [min(p[i] for p in points) - (2 if i != 1 else .75) for i in range(3)]
    hi = [max(p[i] for p in points) + (2 if i != 1 else 1) for i in range(3)]
    selected = []
    for b in boxes:
        if all(b[i+1]<hi[i] and b[i+4]>lo[i] for i in range(3)):
            clipped = b.copy()
            clipped[2] = max(clipped[2],lo[1])
            selected.append(clipped)
    assert summary[name]['result']=='ARRIVED', f'Failed replay: {name}'
    case = dict(id=name,label=labels[name],start=trace[0][:3],goal=goals[name],after=trace,boxes=selected)
    if name in before:
        case['before']=before[name]
        case['beforeFailed']=name=='wool_diagonal_step'
    cases.append(case)
data = dict(cases=cases,tops=[[x,z,y] for (x,z),y in tops.items()])
template = (root/'scripts/parkour-replay-template.html').read_text(encoding='utf-8')
output, replacements = re.subn(r'/\*REPLAY_DATA\*/\s*\{\}', lambda _: json.dumps(data,separators=(',',':'),ensure_ascii=True), template, count=1)
assert replacements == 1, 'Replay data placeholder is missing'
destination = root/'diagnostics/parkour-replay.html'
destination.write_text(output,encoding='utf-8')
assert destination.stat().st_size < 1_000_000
print(f'{destination}: {destination.stat().st_size} bytes, {len(cases)} captured native replays')