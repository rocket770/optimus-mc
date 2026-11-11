# Combat

The rule controller owns aim, attacks, targets and navigation. Optional local ML can advise eligible close-combat movement; fresh configurations use shadow mode. See [ML modes, recording and training](ML.md).

- `balanced`: default. Tracks the opponent, adjusts spacing and strafing, reacts to hits, and checks the current hit ray before attacking.
- `baseline`: simpler distance/angle checks for comparison.
- `paced`: comparison profile with slower turns and attack pacing.

Choose one with `/o profile balanced`; changing it preserves an active run. `/o seed 42` makes combat randomness repeatable. `/o weapon sword|fist` switches the weapon; fist mode requires empty hotbar slot 2.

Combat owns close-range movement. Pursuit uses navigation when needed; committed jumps retain their landing controls.