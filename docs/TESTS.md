# Tests

Java 8 is required.

Headless runtime isolation and server identity: `clientRuntimeSelfTest headlessConnectionSelfTest`. Launcher provisioning checks:
`python -m unittest discover -s headless/tests -v`. See the [headless guide](../headless/README.md)
for a real offline client startup and the separate live-server smoke test.

Headless recovery: `sessionRecoverySelfTest headlessRecoverySelfTest`. These cover
limbo evidence, Duels lobby 1 selection, normal verification before resumed movement,
bounded reconnect/probe timing, unknown-screen timeout, container closure, death
screens, preserving intent across world changes and cancelling everything on stop.

```text
./gradlew --no-daemon build
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-core-tests.ps1
bash scripts/run-core-tests.sh
```

The build runs core checks, disconnected Minecraft adapter tests, navigation replays and native map readers. The standalone runners cover the dependency-free core.

After a build, run `node scripts/test-editor-bootstrap.js`, `node scripts/test-waypoint-editor.js` and `node scripts/test-map-import.js` for editor checks.

[Current results](../diagnostics/verification.txt) record the latest run. These are offline regressions; they do not replace a live-client smoke test.

ML checks: `dgmlMovementModelSelfTest hybridMovementPolicySelfTest killFeedSelfTest learningSelfTest learningShutdownSelfTest`. Python data checks: `python -m unittest discover -s ml/tests -t ml`. These cover abstraction substitution, shadow/live/off, geometry veto, persistence failures, asynchronous life/outcome recording and JVM shutdown flush, holdout separation and candidate export; they do not verify live Hypixel improvement.

Focused settings and guard checks: `settingsPersistenceSelfTest routeConfigurationSelfTest guardControllerSelfTest guardInputRelaySelfTest guardWindowsKeysSelfTest guardCombatSimulationSelfTest`. These cover saved preferences, preserving settings, controller/operator permissions, single-player protection, active-run continuity, explicit window/account input ownership, AFK recovery, collision checks and 65 delayed movement/combat scenarios. The guard checks also cover distant aim ownership, blocked corner recovery and source-aware damage-feedback waits and retaining an opponent across delayed head updates. The latency model includes 250 ms RTT and jitter; it cannot reproduce Hypixel's private server implementation.

`groundContactSelfTest` and `lobbyMovementSelfTest` cover full-foot support, partial ledges, airborne rejection and resuming a two-cell search without changing native flags.

`guardTravelSelfTest` checks 42 distant guard/target pursuits with both input sources and damage recovery. `guardWaitingSelfTest` covers minute-long patrols, confined terrain and returning during a jump.

`guardPressureSelfTest` checks 48 single-swing and defensive exchanges, requiring two or three accepted modeled hits across latency, phase and jitter. `stairDescentSelfTest` checks changed, blocked and unloaded stair corridors; the stair replays cover ascent and descent in four directions.

`guardEngagementSelfTest` checks 18 single-swing exchanges across range, knockback and latency, including approach and return to following. `guardReleaseSelfTest` checks stopping across 32 input, latency and jitter cases, including all three client control phases. `combatHandoffSelfTest` covers alive teleports back to spawn and late lobby menus.

`guardParitySelfTest` checks 97 replays and 19,546 native states. All saved states remain unchanged from 1.0.9, including stopping, catch-up, target locks and damage-feedback timing; [seven deferred step-follow failures](KNOWN_ISSUES.md) remain recorded. Offline checks still need a live-client smoke test.