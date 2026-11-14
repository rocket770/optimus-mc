"""Coordinates provisioning, configuration and child processes at the application boundary."""

import argparse
import json
import platform
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Optional, Sequence

from .artifacts import provision
from .locking import profile_lock
from .profile import Profile, launch_arguments

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = PACKAGE_ROOT.parent


def native_platform() -> str:
    system = {"Windows": "windows", "Linux": "linux", "Darwin": "macos"}.get(platform.system())
    machine = {"amd64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(
        platform.machine().lower()
    )
    return f"{system}-{machine}"


def resolve_mod(requested: Optional[Path] = None) -> Path:
    if requested:
        return requested.resolve(strict=True)
    candidates = list(PROJECT_ROOT.glob("Optimus-Forge189-*.jar"))
    candidates += list((PROJECT_ROOT / "build" / "libs").glob("Optimus-Forge189-*.jar"))
    candidates = [path for path in candidates if not path.name.endswith(("-sources.jar", "-dev.jar"))]
    if len(candidates) != 1:
        raise ValueError("Build the mod first, or select one explicitly with --mod <path.jar>.")
    return candidates[0]


def validate_java(java: Path) -> Path:
    java = java.resolve(strict=True)
    result = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=15)
    if result.returncode or 'version "1.8.' not in result.stdout + result.stderr:
        raise ValueError("Forge 1.8.9 requires Java 8. Pass its executable with --java.")
    return java


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare and run an isolated Optimus headless client.")
    parser.add_argument("action", choices=("prepare", "login", "launch", "console"))
    parser.add_argument("--profile", type=Path, default=PROJECT_ROOT / ".headless" / "default")
    parser.add_argument("--java", type=Path, required=True, help="Java 8 executable used by Minecraft")
    parser.add_argument("--mod", type=Path, help="Built, reobfuscated Optimus JAR")
    parser.add_argument("--memory", type=int, default=1024, help="Minecraft maximum heap in MiB")
    parser.add_argument("--server", default="", help="Server to join; bot stays stopped until start")
    parser.add_argument("--offline-smoke", action="store_true", help="Offline menu-only runtime check")
    args = parser.parse_args(argv)
    if not 512 <= args.memory <= 16384:
        parser.error("--memory must be between 512 and 16384 MiB")
    if args.offline_smoke and (args.server or args.action != "launch"):
        parser.error("--offline-smoke requires launch without --server")
    if args.server and args.action != "launch":
        parser.error("--server requires launch")
    try:
        # Reject invalid launch options before downloading or updating any profile files.
        manifest = json.loads((PACKAGE_ROOT / "dependencies.json").read_text(encoding="utf-8"))
        arguments = (
            launch_arguments(manifest["forge"], args.memory, args.server, args.offline_smoke)
            if args.action == "launch" else []
        )
        java = validate_java(args.java)
        target = native_platform()
        if target not in manifest["launchers"]:
            raise ValueError(f"Unsupported platform: {target}")
        profile = Profile(args.profile.resolve())
        mod = resolve_mod(args.mod) if args.action in ("prepare", "launch") else None
        with profile_lock(profile.root):
            if mod:
                profile.install_mod(mod)
            artifact = manifest["launchers"][target]
            launcher = provision(artifact, profile.root / "tools" / artifact["name"], executable=True)
            if args.action in ("prepare", "launch"):
                specifics = manifest["specifics"]
                provision(specifics, profile.game / "mods" / specifics["name"])
            if args.action == "prepare":
                print(f"Prepared {profile.root}. Log in next, then launch.")
                return 0
            command = profile.launcher_command(launcher, java)
            if args.action == "login":
                command += ["login"]
            elif args.action == "launch":
                command += arguments
                print("Optimus starts stopped. Use the Optimus terminal to configure and start it.", flush=True)
                print("HeadlessMC console: gui, close, disconnect, quit. Use --server for bot connections.", flush=True)
            # Inherit the terminal so Microsoft's device login and HMC-Specifics
            # remain interactive. Authentication belongs exclusively to HeadlessMC.
            return subprocess.call(command, cwd=profile.root)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"Headless launch failed: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130