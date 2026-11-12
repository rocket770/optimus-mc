# Guard mode

Select a player in the same Hypixel lobby:

```text
/o guard PlayerName
/o guard status
/o guard off
```

Selecting a player replaces the previous selection, pins the player's current UUID and starts the normal verified-lobby run when needed. Only one player is guarded. Turning guard off returns an active run to ordinary targeting; turning it back on does not require another start command. F9 and explicit stop retain priority.

The bot follows close behind, holds position when settled, and uses navigation and validated sprint jumps to catch up or cross obstacles. Catch-up resumes after damage recovery even when the player is still far away. Clear ground is followed directly; pathfinding handles obstacles. Releasing local movement keys lets nearby following coast to a stop before position correction resumes. Checked stair descents keep momentum and allow short falls between treads. Close combat owns aiming; distant catch-up keeps navigation's heading. Brief combat engagements stay active between swings rather than alternating every tick with the standing guard phase.

Assists use observed swings, probable attackers and recent damage evidence. A nearby air swing can also identify an opponent without waiting for the guarded player's confirmed hit. Identified victims and attackers get a short target lock. The bot closes into reach and sustains the exchange while the guarded player fights. Shared melee briefly waits for hit feedback; server-observed swings use a shorter wait. An unresolved swing keeps the active opponent. Defensive and bot-only attacks remain immediate. It returns to following when the engagement ends. Damage immunity, equipment, real reach and click pacing still apply. The guarded player remains excluded even with target-all enabled. The bot matches a held sword, otherwise selecting a fist. When the guarded player is still, occasional checked movement keeps the following position active. While they are absent, the bot uses the same random patrol as target mode. It resumes following when they return, finishing any committed patrol jump first.

## Input and permissions

Chat commands use `!o`. Operators can select a player, disable guard and query its brief status. Only the controller can change `guard keys`; `guard share` is local-only. Local `/o guard status` includes input-source, key and decision timing diagnostics.

`/o guard keys auto|on|off` controls Windows sampling. Automatic input works immediately while the controller's game is focused and mouse-captured. If several Minecraft clients are open, `/o guard keys bind` lets you lock input to one window: focus it and press F7 within 30 seconds. A binding belongs to that window, process and guarded account. `/o guard keys auto` clears it.

The optional `/o guard share on|off` relay is for a second local modded client; a bot-only installation does not require it. Remote following is still limited by when the server sends player updates. Ping compensation reduces unnecessary waiting but cannot remove network delay.

## Code and learning

`core/guard` contains pure movement and timing policies. In `client/guard`, the controller owns commands/lifecycle; input, movement, threat evidence, positioning and attacks have separate owners. `GuardHost` and `GuardNavigation` connect them to the existing combat and navigation adapters. Native attacks use the shared pipeline.

The movement ML policy remains available for ordinary combat. Guard remains rule-based and can record combat decisions and outcomes through the shared learning session. See [ML scope and training](ML.md).