# Offline movement training

See [ML integration and collection](../docs/ML.md) for runtime controls, schema, checkpoint provenance and training limits.

`optimus_training/prepare.py` prepares completed loss-free lives and combat diagnostics. `optimus_training/train.py` fits a candidate movement policy using exact runtime features, with passive/uncertain samples excluded and independent validation partitions. `optimus_training/combat_diagnostics.py` summarizes aim and delayed attack observations without inventing hit labels.

Run `python -m unittest discover -s ml/tests -t ml` from the repository root for the focused data/export tests. Python 3.9+ is sufficient; no additional packages are required. Generated data belongs in the ignored `ml/live-data/` folder. Candidates are never installed automatically.

```text
ml/
  run.py                  CLI entry point
  optimus_training/       preparation, diagnostics, training and CLI modules
  tests/                  regression suites and shared synthetic fixtures
  live-data/              ignored recordings/prepared data/candidates
```

Use `python ml/run.py prepare --help` or `python ml/run.py train --help` for command options. The package is separated from its tests and can run directly without a packaging/install step.