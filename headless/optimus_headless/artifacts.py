"""Checksum-verified upstream downloads, independent of Minecraft profile setup."""

import hashlib
import os
import shutil
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Mapping


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "Optimus-Headless"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
            shutil.copyfileobj(response, output)
    except urllib.error.URLError as error:
        # Some Windows Python distributions cannot access the system trust store.
        # Windows' bundled curl uses that store and still verifies HTTPS normally.
        curl = shutil.which("curl.exe") if os.name == "nt" else None
        if not isinstance(error.reason, ssl.SSLCertVerificationError) or not curl:
            raise
        subprocess.run(
            [curl, "--fail", "--location", "--silent", "--show-error", "--proto", "=https",
             "--proto-redir", "=https", "--max-time", "120", "--output", str(destination), url],
            check=True, timeout=125,
        )


def provision(artifact: Mapping[str, str], destination: Path, executable: bool = False) -> Path:
    """Publish only complete, verified files; never execute an unverified cache entry."""
    expected = artifact["sha256"]
    if destination.is_file() and sha256(destination) == expected:
        if executable:
            destination.chmod(destination.stat().st_mode | 0o700)
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=destination.parent, suffix=".download")
    os.close(descriptor)
    try:
        downloaded = Path(temporary)
        download(artifact["url"], downloaded)
        if sha256(downloaded) != expected:
            raise ValueError(f"Checksum mismatch for {destination.name}; download rejected.")
        if executable:
            downloaded.chmod(downloaded.stat().st_mode | 0o700)
        os.replace(downloaded, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return destination