# Guard mode

Select a player in the same Hypixel lobby:

```text
/o guard PlayerName
/o guard status
/o guard off
```

Selecting a player replaces the previous selection, pins the player's current UUID and starts the normal verified-lobby run when needed. Only one player is guarded. Turning guard off returns an active run to ordinary targeting; turning it back on does not require another start command. F9 and explicit stop retain priority.

The bot follows close behind, holds position when settled, and uses navigation and validated sprint jumps to catch up or cross obstacles. Ordinary safe step-downs do not need a jump. During combat, aiming follows the opponent independently of follow movement. Brief combat engagements stay active between swings rather than alternating every tick with the standing guard phase.

Assists use observed swings, probable attackers and recent damage evidence. A nearby air swing can also identify an opponent without waiting for the guarded player's confirmed hit. Attacks use the existing reach, geometry, equipment and click-pacing checks. The guarded player remains excluded even with target-all enabled. The bot matches a held sword, otherwise selecting a fist. When the guarded player is still or outside the pit, a checked jump and small movement after 30 seconds provides AFK activity.

## Input and permissions

Chat commands use `!o`. Operators can select a player, disable guard and query its brief status. Only the controller can change `guard keys`; `guard share` is local-only. Local `/o guard status` includes input-source, key and decision timing diagnostics.

`/o guard keys auto|on|off` controls Windows sampling. For a bot-only installation, run `/o guard keys bind`, focus the controller's Minecraft window and press F7 within 30 seconds. The binding stays local to that window, process and guarded account. Switching windows stops direct input; observed movement remains available. A different guarded player never inherits the binding.

The optional `/o guard share on|off` relay is for a second local modded client; a bot-only installation does not require it. Remote following is still limited by when the server sends player updates. Ping compensation reduces unnecessary waiting but cannot remove network delay.

## Code and learning

`core/guard` contains pure movement and timing policies. In `client/guard`, the controller owns commands/lifecycle; input, movement, threat evidence, positioning and attacks have separate owners. `GuardHost` and `GuardNavigation` connect them to the existing combat and navigation adapters. Native attacks use the shared pipeline.

The movement ML policy remains available for ordinary combat. Guard remains rule-based and can record combat decisions and outcomes through the shared learning session. See [ML scope and training](ML.md).