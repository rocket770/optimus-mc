# Headless Optimus

Runs the existing Forge 1.8.9 mod without a game window, using HeadlessMC's LWJGL
patching and HMC-Specifics console. Combat, navigation, world physics and the
Optimus terminal use the same controllers as the desktop client.

## Requirements

- Java 8 for Minecraft, Python 3.9+ for setup, and internet access on first use.
- A purchased Minecraft Java account for connecting to servers. Microsoft device
  login and token refresh are handled by HeadlessMC.
- Windows x64, Linux x64/ARM64, or macOS x64/ARM64. See the upstream
  [native runtime prerequisites](https://headlesshq.github.io/headlessmc/getting-started/).

The native launcher needs no separate modern Java installation. `dependencies.json`
pins HeadlessMC 3.0.0-RC8, HMC-Specifics 3.0.1 for Forge 1.8.9, and Forge
11.15.1.2318. Launcher and companion downloads are checked against their published
SHA-256 before use. Third-party binaries are fetched locally, outside Optimus bundles.
On Windows, Python certificate-store failures fall back to the system `curl.exe`
HTTPS transport; certificate verification and checksum checks stay enabled.

## Windows

Build the mod with Java 8 first (`.\gradlew.bat --no-daemon build`). From the
repository or extracted release directory:

```powershell
$java8 = 'C:\Program Files\Java\jdk1.8.0_351\bin\java.exe'
.\headless\Optimus-Headless.ps1 prepare --java $java8
.\headless\Optimus-Headless.ps1 login --java $java8
.\headless\Optimus-Headless.ps1 launch --java $java8 --server mc.hypixel.net
```

Use your installed Java 8 path. If script execution is restricted, invoke the
wrapper through `powershell -NoProfile -ExecutionPolicy Bypass -File`.

## Linux / macOS

```sh
python3 headless/run.py prepare --java /path/to/java8/bin/java
python3 headless/run.py login --java /path/to/java8/bin/java
python3 headless/run.py launch --java /path/to/java8/bin/java --server mc.hypixel.net
```

`launch` also prepares or updates the profile, so `prepare` is optional. Specify
`--mod /path/to/Optimus-Forge189-1.0.10.jar` if multiple builds exist. The sources
JAR is not runnable. Build changes require restarting the client.

## Control and profiles

The client starts stopped. Open `terminal/Optimus-Terminal.cmd` on Windows, select
the account with `use BotName`, and use the normal settings, `start`, `status` and
`stop` commands. A headless client has no physical F9 button; use terminal `stop`.
Stopping the bot keeps the game connected. In the HeadlessMC console, `quit` exits
the game; `disconnect` leaves the server. Closing the Optimus terminal does not
stop a bot. Keep the launcher terminal open while the client runs.

The HeadlessMC console also supports `gui`, `click`, and `close` for menus.
Use the wrapper's `--server` option to connect: it uses Minecraft's normal
`ServerData` connection path so Optimus can recognize the server. The upstream
Forge 1.8.9 console `connect` command omits that metadata, and its chat command
forwarding bypasses local Forge commands such as `/o`. Use the Optimus terminal
for bot controls. `console` opens the launcher shell for account
management or troubleshooting. Use this wrapper's `launch` action to start Optimus
with the correct headless JVM properties.

The default profile is `.headless/default/`. Use `--profile .headless/BotName` on
**every** command for a second account. Each profile owns its launcher account
store, Minecraft downloads, game directory, logs, mods and settings. A process lock
prevents two wrapper instances from preparing or running the same profile at once.
Use `--memory 1024` to change the maximum heap in MiB. Minecraft still loads chunks,
entities and Forge; headless operation does not remove those memory costs.

Existing desktop settings are not copied automatically. While stopped, copy the
desired `config/optimus.properties` and route files into `<profile>/game/config/`,
or configure the profile through the Optimus terminal. Preparation preserves
existing profile settings and unrelated mods. Runtime presentation settings disable
background pause, vsync, desktop mouse polling and Optimus overlays. The launcher
uses dummy assets to reduce downloads and asset memory. Guard input relay works
with a visible controller client; Windows desktop input sampling still needs a
focused controller window on the same desktop.

Account stores contain credentials. Keep profiles private and outside shared source
or release folders; the default `.headless/` path is ignored by Git. Do not run a
separate HeadlessMC process against a profile while this wrapper owns it.

## Automatic recovery

An authorized headless run retains its intent through an interrupted connection or
lobby transfer. It releases gameplay input, checks `/locraw`, returns from limbo or
the main lobby to Duels, switches to lobby 1, and repeats normal lobby verification
before moving again. Starting explicitly while in limbo follows the same recovery
path. Recovery does not run while the bot is stopped.

During a run, chat, pause, inventory and server container menus close automatically.
Container closure uses the normal player close action, without selecting slots.
Death screens request respawn with a two-second backoff. Unknown screens are left
untouched; a screen that blocks the run for 30 seconds stops the bot with a reason.
Account prompts, purchases and other unknown buttons are never accepted automatically.

Each recovery has a 60-second deadline, with location probes at four-second
intervals and at most three reconnect attempts separated by ten seconds. A run
allows at most three recovery cycles; another explicit start resets that budget.
Unexpected game matches or unsupported location replies stop recovery. `stop` and
F9 cancel pending recovery immediately, including any queued rejoin or restart.
Desktop clients retain their existing menu and transfer behavior.

## Code structure

`core/safety/SessionRecovery` owns the recovery state machine and deadlines without
Minecraft dependencies. `client/HeadlessRecovery` translates its actions through
the event handler's small host interface. `client/runtime/` owns startup, menus and
the shared injectable `ServerConnector`. `ClientEvents` retains gameplay authorization
and supplies only location replies scoped to a pending request. World changes discard
queued location evidence before recovery can resume.

The Python launcher separates argument coordination (`cli.py`), profile files and
launch arguments (`profile.py`), verified downloads (`artifacts.py`), and process
locking (`locking.py`). Invalid launch options are rejected before modifying a profile.

## Verification

```text
python -m unittest discover -s headless/tests -v
./gradlew --no-daemon clientRuntimeSelfTest remoteControlSelfTest combatHandoffSelfTest
```

`headlessConnectionSelfTest` checks server identity, startup timing and the single
connection attempt without a display or network connection. Launcher checks reject
server addresses that could inject JVM options.
`sessionRecoverySelfTest` covers the independent recovery policy, retry limits and
cancellation. `headlessRecoverySelfTest` drives actual client location events and
the packet observer, then checks menu closure, respawn requests and stop behavior.

An offline runtime check needs no account and does not join a server:

```powershell
.\headless\Optimus-Headless.ps1 launch --java $java8 --offline-smoke
```

Wait for Minecraft to load, run `gui`, verify the main menu is available, then run
`quit`. This exercises real Forge/mod startup and LWJGL patching; it does not verify
server login, combat, respawning or movement. For a live smoke test, log in with a
dedicated profile, connect, confirm terminal discovery and status, exercise the
configured navigation/combat flow, then check `stop`, respawning and disconnect
recovery. Menus and existing world/server authorization still apply.

Rendering optimizations are optional and are not installed by default. First verify
normal headless play before adding the Forge 1.8.9 build of
[HMC-Optimizations](https://github.com/headlesshq/hmc-optimizations).

With the pinned upstream versions, Forge can report an ASM parsing warning for
`META-INF/versions/9/module-info.class` inside `headlessmc-lwjgl.jar`. The offline
Windows smoke test reached the main menu, accepted console commands and exited
successfully despite this upstream library-discovery warning.
It also verified terminal discovery and local `status`/`stop` commands. Server
authentication and live gameplay still require a separate account-backed smoke test.

Upstream: [launching](https://headlesshq.github.io/headlessmc/launch/),
[console controls](https://headlesshq.github.io/headlessmc/specifics/), and
[HeadlessMC source and license](https://github.com/headlesshq/headlessmc).