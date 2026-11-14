# Optimus

A Forge 1.8.9 combat and navigation client, with an offline map editor and a Windows terminal.

Put the JAR in `mods/`, restart Minecraft, then run `/o help`. Chat commands use `!o`. F8 starts; F9 stops.

- [Running the bot and commands](docs/BOT.md)
- [Guard mode](docs/GUARD.md)
- [Headless client setup](headless/README.md)
- [Settings](docs/CONFIGURATION.md)
- [Combat profiles](docs/COMBAT.md)
- [Optional movement ML and training](docs/ML.md)
- [Navigation and route editing](docs/NAVIGATION.md)
- [Map downloads and imports](docs/MAPS.md)
- [Editor](editor/README.md) / [Terminal](terminal/README.txt) / [Tests](docs/TESTS.md)
- [Example pit world and config](examples/pit/README.md)

Build with Java 8: `./gradlew --no-daemon build sourceBundle` (`.\gradlew.bat` on Windows). The release ZIP in `build/libs/` includes the mod, editor and terminal.

Personal, noncommercial use. See [LICENSE](LICENSE) and [disclaimer](DISCLAIMER.md).