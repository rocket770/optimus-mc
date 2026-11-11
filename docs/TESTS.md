# Tests

Java 8 is required.

```text
./gradlew --no-daemon build
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-core-tests.ps1
bash scripts/run-core-tests.sh
```

The build runs core checks, disconnected Minecraft adapter tests, navigation replays and native map readers. The standalone runners cover the dependency-free core.

After a build, run `node scripts/test-editor-bootstrap.js`, `node scripts/test-waypoint-editor.js` and `node scripts/test-map-import.js` for editor checks.

[Current results](../diagnostics/verification.txt) record the latest run. These are offline regressions; they do not replace a live-client smoke test.

ML checks: `dgmlMovementModelSelfTest hybridMovementPolicySelfTest killFeedSelfTest learningSelfTest`. Python data checks: `python -m unittest discover -s ml/tests -t ml`. These cover abstraction substitution, shadow/live/off, geometry veto, persistence failures, asynchronous life/outcome recording, holdout separation and candidate export; they do not verify live Hypixel improvement.