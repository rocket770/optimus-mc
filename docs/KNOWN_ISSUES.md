# Known issues

## Guard following over steps

Deferred on 12 November 2026. Seven simulated cases still exceed the following limits at 250 ms round-trip latency:

| Terrain | Input | Update offset / jitter | Failure |
| --- | --- | --- | --- |
| One-block drop | Windows keys | 0 / 40 ms | 3.75-block separation; limit 3.25 |
| One-block drop | Observed | 0 / 40 ms | 4.09-block separation; limit 3.25 |
| One-block drop | Observed | 25 / 10 ms | Unnecessary jump |
| One-block drop | Observed | 25 / 25 ms | 4.02-block separation; limit 3.25 |
| One-block drop | Observed | 25 / 40 ms | 4.02-block separation; limit 3.25 |
| Half-block rise | Observed | 0 / 40 ms | 2.68-block separation; limit 2.25 |
| Half-block rise | Observed | 25 / 40 ms | 2.58-block separation and unnecessary jump; limit 2.25 |

All recover within eight centimetres of the following point in the replay. `guardParitySelfTest` records these failures and checks that their behaviour has not changed; a passing parity result does not mark them fixed.