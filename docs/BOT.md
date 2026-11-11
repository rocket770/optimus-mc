# Running the bot

Install the JAR in a Forge 1.8.9 client's `mods/` folder. In a supported Duels lobby:

```text
/o locraw
/o preset supplied
/o controller MainAccount
```

The preset is for the [example pit](../examples/pit/README.md). Pair the controller while both accounts are present. From that account's chat:

```text
!o target add Opponent
!o start
!o stop
```

Useful commands: `target all|listed`, `target clear`, `weapon sword|fist`, `profile balanced|baseline|paced`, `parkour on|off`, `visuals on|off`, and `status`. Use `operator add Name` to grant target, guard, weapon and start/stop control.

Local commands use `/o`; chat uses `!o`. `!o @BotName stop` addresses one bot. F8 toggles a run; F9 always stops it. Settings live in `config/optimus.properties`. See [settings](CONFIGURATION.md).

Use `/o guard PlayerName` or `!o guard PlayerName` to select one guarded player and start following. `/o guard off` returns to ordinary combat without stopping an active run. See [guard commands and input sources](GUARD.md).

Optional learning: `/o ml off|shadow|live|reload|status` and `/o logging on|off|status` (`record` is an alias). Mode and recording changes preserve the active run. Remote changes are controller-only. See [ML setup and scope](ML.md).