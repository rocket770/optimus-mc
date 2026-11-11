# Optional combat movement learning

Optimus includes a local movement model and an offline recording/training pipeline. The rule controller still selects targets, aims, attacks, chooses weapons and owns navigation. Learned movement is considered only during eligible, visible, grounded balanced combat; deliberate taps, escape responses, committed jumps and routing retain their existing controls. Every applied recommendation passes the controller's final native geometry checks.

## Commands and settings

- `/o ml status` reports the mode, loaded models, suggestions, applied actions and errors.
- `/o ml shadow` evaluates recommendations while using the rule controller's movement.
- `/o ml live` allows eligible recommendations to influence movement.
- `/o ml off` bypasses inference. Recording can remain enabled.
- `/o ml reload` reloads local settings/models. A malformed configuration leaves the running settings unchanged.
- `/o logging on|off|status` controls fight recording; `/o record` is an alias.

These changes do not stop or start gameplay. Logging on while stopped arms the next normal start. Remote `!o ml` and `!o logging` commands require the controller role; operators cannot change them. Responses are brief.

Fresh installations use shadow mode and recording off. Settings live in `config/optimus-ml.properties`; an example is in `ml/optimus-ml.properties.example`. Supported keys are `mode=off|shadow|live`, `confidence=0.35..0.99`, `fusion=hybrid|neural` and `record=true|false`. Existing `duelguard-ml.properties` preferences are read when the Optimus file is absent; subsequent changes write only the Optimus file. Unknown properties are preserved, and writes replace files atomically where supported.

The primary override is `config/optimus-ml-model.dgml`. The legacy `duelguard-ml-model.txt` is also readable. Without a valid override, the bundled checkpoint is used and an override error is reported. An optional compatible second expert can be placed at `config/optimus-combat-model.dgml`; `fusion=neural` disables it and tactical fusion. No Python, GPU, network request or checkpoint download is needed in Minecraft.

## Code boundaries

- `core/ml/MovementAdvisor`: the narrow steering port used by `CombatController`.
- `core/ml/MovementFeatures`: the immutable sixteen-feature schema and its encoding.
- `core/ml/MovementModel`: model abstraction; `DgmlMovementModel` reads bounded DGML1/DGML2 files and abstains on malformed, uncertain or out-of-distribution observations.
- `core/ml/HybridMovementPolicy`: arbitration between model recommendations, baseline input and the optional tactical rule. Tactical suggestions are counted separately from model suggestions.
- `learning/LearningRuntime`: off/shadow/live inference and counters, independent of Minecraft.
- `learning/LearningConfiguration`, `ModelRepository`, `LearningSession`: settings persistence, local model loading and lifecycle composition.
- `learning/recording/CombatRecording`: observation-only recorder port; client adapters cannot open or close writer sessions.
- `learning/recording/FightRecorder`: bounded asynchronous JSONL writer and anonymous life/opponent/outcome correlation, with no Minecraft imports.
- `client/learning/CombatTelemetry`: the only native observation adapter. It converts actual entities, controls, aim rays and ping into recorder events.
- `ml/optimus_training/`: offline preparation and candidate fitting. These tools never install a candidate or alter the running client.

Controllers do not parse model files, maintain writer threads or reconstruct training features. The client supplies the exact sixteen features used at the movement hook. The model cannot grant control, extend attack reach or bypass the existing safety checks.

## Recordings and diagnostics

Use `/o logging on` during ordinary FFA combat. Data is written under the game directory's `config/optimus-ml-data/fights`. Both players' observed weapons, health, absorption, hurt state, movements and swings are retained. Aim/timing decisions distinguish actual attack requests from animation-only swings and record aim errors, body ray checks, pacing gates, own reported ping and opponent reported ping when available. No account names, account UUIDs, server addresses or raw chat are serialized.

Independent life boundaries record actual deaths, respawn transitions and same-world returns from the configured FFA pit. The server kill feed is matched against the client's actual username and recent opponent names in memory, then stored anonymously. A death observed through multiple mechanisms is correlated rather than counted repeatedly. Changing target or losing an opponent does not hide a life-ending death.

Remote health or hurt following an attack does not establish that this bot caused the damage in a crowd. Prepared attack reviews retain unknown attribution. Missing damage is not treated as a confirmed miss. The observer never controls or overrides server state.

## Offline preparation and training

Python 3.9+ is required only for these tools; there are no additional Python dependencies.

On Windows, run `scripts/prepare-fight-data.ps1` and then `scripts/train-fight-model.ps1`. The former accepts `-GameDirectory` for a separate launcher instance; both accept explicit output directories. Portable direct commands are:

```text
python ml/run.py prepare <game-dir>/config/optimus-ml-data/fights --out ml/live-data/prepared-example
python ml/run.py train ml/live-data/prepared-example --out ml/live-data/candidate-example --self-weapon fist
python -m unittest discover -s ml/tests -t ml
```

Preparation exports movement transitions, life/episode reports and aim/timing diagnostics. It can consume completed, loss-free lives while schema-2 recording continues; open or lossy lives are withheld. An indexed temporary database keeps preparation bounded in memory. Legacy recordings remain readable for analysis, but missing features or swing evidence are not invented for training.

Fitting uses the existing nine movement actions and sixteen runtime features. Passive/uncertain opponents are excluded from fitting, weapon/armour/crowd contexts are balanced, and whole sessions or purged chronological lives separate validation from training. At least 500 eligible training windows, 100 validation windows and two observed actions are required. Insufficient data produces a report and no model. Candidate exports retain the Java DGML2 confidence and distribution checks.

The method is outcome-weighted behaviour cloning, not counterfactual reinforcement learning. It trains movement only. Aim and attack timing are recorded for diagnosis and remain rule-based. Held-out action agreement is not proof of better live PvP. Validate a candidate separately before copying it to the model override path. Private datasets and candidates under `ml/live-data/` are ignored and excluded from source archives.

## Restored checkpoint

The bundled `assets/optimus/ml/movement.dgml` restores the numeric parameters from the previous local DuelGuard ML installation. Its SHA-256 is `7dfe625854d1d90c07f78ae35fa3ff59074dc54271f049261b62cb2af066dec4`. The original file had SHA-256 `a913c02bf3cbc63e63e6b4d5381f7075acd32b8eafb7429fd1529a1b2902c0a4`; only CRLF/terminal-newline normalization changes the file bytes. DGML format identifiers and the `DGML16-v1` feature contract are retained for compatibility. It is a restored baseline, not newly trained or verified as an improvement on live Hypixel.