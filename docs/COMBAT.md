# Combat

Combat is rule-based; no trained model or weight files are shipped.

- `balanced`: default. Tracks the opponent, adjusts spacing and strafing, reacts to hits, and checks the current hit ray before attacking.
- `baseline`: simpler distance/angle checks for comparison.
- `paced`: comparison profile with slower turns and attack pacing.

Choose one with `/o profile balanced`, then `/o start`. `/o seed 42` makes combat randomness repeatable. `/o weapon sword|fist` switches the weapon; fist mode requires empty hotbar slot 2.

Combat owns close-range movement. Pursuit uses navigation when needed; committed jumps retain their landing controls.