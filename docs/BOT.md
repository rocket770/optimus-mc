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

Useful commands: `target all|listed`, `target clear`, `weapon sword|fist`, `profile balanced|baseline|paced`, `parkour on|off`, `visuals on|off`, and `status`. Use `operator add Name` to grant target, weapon and start/stop control.

Local commands use `/o`; chat uses `!o`. `!o @BotName stop` addresses one bot. F8 toggles a run; F9 always stops it. Settings live in `config/optimus.properties`.