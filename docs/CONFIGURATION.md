# Settings

Files live in the Minecraft game directory's `config/` folder:

- `optimus.properties`: controller, operators, targets, guard, combat and arena geometry.
- `optimus-ml.properties`: learning mode, confidence and recording.
- `optimus-ml-model.dgml`: optional movement model override.
- `optimus-route-hints.json` and `optimus-route-map.json`: custom navigation data.

The client reads these filenames directly. Saved preferences and unknown properties survive writes; loading never rewrites arena bounds or enables a disabled setting. Writes replace files atomically where supported.

`/o status` reports configuration errors. `/o guard`, `/o routes`, `/o profile` and `/o seed` save changes without stopping an active run. F9 always stops.

Fresh settings use the configured default controller. `/o controller Name` pairs another account; `/o controller off` disables chat control.