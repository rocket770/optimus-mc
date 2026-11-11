"""Command-line boundary for offline preparation and candidate fitting."""
import argparse
import json
from pathlib import Path

from .prepare import prepare
from .train import train


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    preparation = commands.add_parser('prepare', help='prepare completed recorded lives')
    preparation.add_argument('recordings', type=Path)
    preparation.add_argument('--out', type=Path, required=True)
    fitting = commands.add_parser('train', help='fit an uninstalled movement candidate')
    fitting.add_argument('prepared', type=Path)
    fitting.add_argument('--out', type=Path, required=True)
    fitting.add_argument('--self-weapon', choices=['fist', 'sword', 'any'], default='fist')
    fitting.add_argument('--epochs', type=int, default=25)
    fitting.add_argument('--max-samples', type=int, default=30000)
    args = parser.parse_args(arguments)
    try:
        if args.command == 'prepare':
            files = list(args.recordings.glob('fight-*.jsonl'))
            if not files:
                parser.error('No fight recordings found; use /o logging on during normal PvP.')
            result = prepare(files, args.out)
        else:
            if not 1 <= args.epochs <= 200 or not 500 <= args.max_samples <= 200000:
                parser.error('Use 1..200 epochs and 500..200000 samples.')
            result = train(args.prepared, args.out, args.self_weapon, args.max_samples,
                           args.epochs, progress=True)
        print(json.dumps(result, indent=2))
        if args.command == 'train' and result['status'] != 'candidate_requires_validation':
            print('No model written. '+result['reason'])
            return 2
        return 0
    except (OSError, ValueError) as error:
        parser.exit(1, str(error)+'\n')