# Settings

Files live in the Minecraft game directory's `config/` folder:

- `optimus.properties`: controller, operators, targets, guard, combat and arena geometry.
- `optimus-ml.properties`: learning mode, confidence and recording.
- `optimus-ml-model.dgml`: optional movement model override.
- `optimus-route-hints.json` and `optimus-route-map.json`: custom navigation data.

The client reads these filenames directly. Saved preferences and unknown properties survive writes; loading never rewrites arena bounds or enables a disabled setting. Writes replace files atomically where supported.

`/o status` reports configuration errors. `/o guard`, `/o routes`, `/o profile` and `/o seed` save changes without stopping an active run. F9 always stops.

Fresh settings use the configured default controller. `/o controller Name` pairs another account; `/o controller off` disables chat control.

Use `/o exceptions add Name` to exclude a player from targeting and attacks in every mode, including Target All and Guard defense. Names match exactly and ignore case. Exceptions override the named target list and persist in `target.exceptions` in `optimus.properties`. Changes apply immediately during an active run.

`/o exceptions remove Name`, `/o exceptions list` and `/o exceptions clear` manage the same list. `/o target exceptions` and `/o guard exceptions` accept the same subcommands. Clearing targets or switching modes preserves exceptions. Paired controllers and operators can manage exceptions through chat control.