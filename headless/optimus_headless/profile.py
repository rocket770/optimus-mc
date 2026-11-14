"""Profile files and upstream CLI translation, with no network or process ownership."""

import os
import re
import shutil
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Profile:
    root: Path

    @property
    def game(self) -> Path:
        return self.root / "game"

    def install_mod(self, mod: Path) -> None:
        with zipfile.ZipFile(mod) as archive:
            if "dev/optimus/client/OptimusMod.class" not in archive.namelist():
                raise ValueError("Choose the built Optimus mod JAR, not the sources JAR.")
        mods = self.game / "mods"
        mods.mkdir(parents=True, exist_ok=True)
        # A dedicated managed filename lets later builds replace the same mod safely.
        for candidate in mods.rglob("*.jar"):
            if candidate == mods / "optimus.jar":
                continue
            with zipfile.ZipFile(candidate) as archive:
                if "dev/optimus/client/OptimusMod.class" in archive.namelist():
                    raise ValueError(f"Duplicate Optimus mod in profile: {candidate}")
        descriptor, temporary = tempfile.mkstemp(dir=mods, suffix=".tmp")
        try:
            with os.fdopen(descriptor, "wb") as output, mod.open("rb") as source:
                shutil.copyfileobj(source, output)
            os.replace(temporary, mods / "optimus.jar")
        finally:
            Path(temporary).unlink(missing_ok=True)

    def launcher_command(self, launcher: Path, java: Path) -> list[str]:
        # HeadlessMC 3 properties must precede the command. Paths are individual
        # process arguments, never shell expressions or interpolated command text.
        return [
            str(launcher),
            f"-Dhmc.files.location={self.root / 'launcher'}",
            f"-Dhmc.files.mc={self.root / 'minecraft'}",
            f"-Dhmc.files.game={self.game}",
            "-Dhmc.files.game-for-each-version=false",
            "-Dhmc.assets.dummy=true",
            f"-Dhmc.java.versions={java.parent.parent.as_posix()}",
            "-Dhmc.java.download=false",
        ]


def launch_arguments(forge: str, memory: int, server: str = "", offline: bool = False) -> list[str]:
    if server and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]{1,5})?", server):
        raise ValueError("Server must be a hostname or IPv4 address with an optional port.")
    if server and ":" in server and not 1 <= int(server.rsplit(":", 1)[1]) <= 65535:
        raise ValueError("Server port must be between 1 and 65535.")
    jvm = f"-Xms256M -Xmx{memory}M -Doptimus.headless=true"
    if server:
        jvm += f" -Doptimus.server={server}"
    arguments = [
        "launch", "--headless",
        f"--jvm={jvm}",
    ]
    if offline:
        if server:
            raise ValueError("Offline smoke tests cannot connect to a server.")
        arguments.append("--offline")
    return arguments + ["forge", "1.8.9", forge]