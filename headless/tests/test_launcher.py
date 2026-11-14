"""Offline launcher contracts: no accounts, network, display or game process."""

import hashlib
import io
import json
import subprocess
import ssl
import sys
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from optimus_headless.artifacts import provision
from optimus_headless.cli import main, resolve_mod, validate_java
from optimus_headless.locking import profile_lock
from optimus_headless.profile import Profile, launch_arguments


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="optimus profile ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def make_mod(self, path, payload=b"compiled mod"):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("dev/optimus/client/OptimusMod.class", payload)
        return path

    def test_checksum_failure_preserves_existing_file_and_cleans_download(self):
        destination = self.root / "launcher"
        destination.write_bytes(b"existing")
        artifact = {"url": "https://example.invalid/launcher", "sha256": "0" * 64}
        with patch("urllib.request.urlopen", return_value=io.BytesIO(b"invalid")):
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                provision(artifact, destination)
        self.assertEqual(b"existing", destination.read_bytes())
        self.assertEqual([destination], list(self.root.iterdir()))

    def test_verified_download_can_be_reused_offline(self):
        payload = b"verified artifact"
        artifact = {"url": "https://example.invalid/mod", "sha256": hashlib.sha256(payload).hexdigest()}
        destination = self.root / "tools" / "mod.jar"
        with patch("urllib.request.urlopen", return_value=io.BytesIO(payload)) as download:
            provision(artifact, destination)
            provision(artifact, destination)
            self.assertEqual(1, download.call_count)
        self.assertEqual(payload, destination.read_bytes())

    def test_windows_trust_fallback_retains_https_and_checksum_verification(self):
        payload = b"system trust store download"
        artifact = {"url": "https://example.invalid/mod", "sha256": hashlib.sha256(payload).hexdigest()}
        destination = self.root / "mod.jar"

        def system_download(command, **kwargs):
            self.assertIn("=https", command)
            self.assertNotIn("--insecure", command)
            Path(command[command.index("--output") + 1]).write_bytes(payload)

        error = urllib.error.URLError(ssl.SSLCertVerificationError("Python trust store unavailable"))
        with patch("urllib.request.urlopen", side_effect=error), \
                patch("optimus_headless.artifacts.os.name", "nt"), \
                patch("shutil.which", return_value="curl.exe"), \
                patch("subprocess.run", side_effect=system_download):
            provision(artifact, destination)
        self.assertEqual(payload, destination.read_bytes())

    def test_mod_update_preserves_settings_and_unrelated_mods(self):
        profile = Profile(self.root / "profile")
        settings = profile.game / "config" / "optimus.properties"
        settings.parent.mkdir(parents=True)
        settings.write_bytes(b"existing settings")
        profile.install_mod(self.make_mod(self.root / "first.jar"))
        other = profile.game / "mods" / "other.jar"
        with zipfile.ZipFile(other, "w") as archive:
            archive.writestr("other.class", b"other mod")
        profile.install_mod(self.make_mod(self.root / "second.jar", b"new mod"))
        self.assertEqual(b"existing settings", settings.read_bytes())
        self.assertTrue(other.is_file())
        with zipfile.ZipFile(profile.game / "mods" / "optimus.jar") as archive:
            self.assertEqual(b"new mod", archive.read("dev/optimus/client/OptimusMod.class"))

    def test_sources_and_duplicate_mods_are_rejected(self):
        profile = Profile(self.root / "profile")
        source = self.root / "sources.jar"
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("OptimusMod.java", b"source")
        with self.assertRaisesRegex(ValueError, "built Optimus"):
            profile.install_mod(source)
        mod = self.make_mod(self.root / "mod.jar")
        profile.install_mod(mod)
        self.make_mod(profile.game / "mods" / "old-optimus.jar")
        with self.assertRaisesRegex(ValueError, "Duplicate Optimus"):
            profile.install_mod(mod)

    def test_profiles_are_isolated_and_paths_remain_single_arguments(self):
        first, second = Profile(self.root / "first"), Profile(self.root / "second")
        java = self.root / "Java 8" / "java.exe"
        launcher = self.root / "launcher.exe"
        command = first.launcher_command(launcher, java)
        self.assertEqual(str(launcher), command[0])
        self.assertIn(f"-Dhmc.files.game={first.game}", command)
        self.assertIn(f"-Dhmc.java.versions={java.parent.parent.as_posix()}", command)
        self.assertNotEqual(command, second.launcher_command(launcher, java))

    def test_lock_rejects_concurrent_use_and_releases_after_failure(self):
        with self.assertRaisesRegex(ValueError, "simulate"):
            with profile_lock(self.root):
                with self.assertRaisesRegex(RuntimeError, "already in use"):
                    with profile_lock(self.root):
                        self.fail("Concurrent profile access allowed")
                raise ValueError("simulate child failure")
        with profile_lock(self.root):
            pass
        self.assertEqual(1, (self.root / ".profile.lock").stat().st_size)

    def test_launch_is_headless_and_bot_start_remains_explicit(self):
        command = launch_arguments("11.15.1.2318", 1024, "localhost:25565")
        self.assertIn("--headless", command)
        self.assertIn("--jvm=-Xms256M -Xmx1024M -Doptimus.headless=true -Doptimus.server=localhost:25565", command)
        self.assertFalse(any(argument.startswith("--server=") for argument in command))
        self.assertEqual(["forge", "1.8.9", "11.15.1.2318"], command[-3:])
        self.assertNotIn("--offline", command)
        self.assertNotIn("start", command)
        with self.assertRaisesRegex(ValueError, "cannot connect"):
            launch_arguments("forge", 1024, "localhost", offline=True)

    def test_server_cannot_inject_jvm_arguments_or_invalid_ports(self):
        for server in ("localhost -Dother=true", 'localhost"', "localhost:0", "localhost:65536"):
            with self.subTest(server=server), self.assertRaises(ValueError):
                launch_arguments("forge", 1024, server)

    def test_invalid_launch_options_fail_before_profile_mutations_or_downloads(self):
        with patch("optimus_headless.cli.validate_java") as java, \
                patch("optimus_headless.cli.profile_lock") as lock, \
                patch("optimus_headless.cli.provision") as download, \
                patch("sys.stderr", new_callable=io.StringIO):
            result = main(["launch", "--java", str(self.root / "java"),
                           "--server", "localhost -Dunexpected=true"])
        self.assertEqual(1, result)
        java.assert_not_called()
        lock.assert_not_called()
        download.assert_not_called()

    def test_java_validation_rejects_modern_java_and_failed_processes(self):
        java = self.root / "java"
        java.touch()
        for code, version in [(0, 'openjdk version "17.0.2"'), (1, 'java version "1.8.0"')]:
            result = subprocess.CompletedProcess([], code, stdout="", stderr=version)
            with patch("subprocess.run", return_value=result):
                with self.assertRaisesRegex(ValueError, "Java 8"):
                    validate_java(java)
        result = subprocess.CompletedProcess([], 0, stdout="", stderr='openjdk version "1.8.0_351"')
        with patch("subprocess.run", return_value=result):
            self.assertEqual(java.resolve(), validate_java(java))

    def test_ambiguous_builds_require_explicit_selection(self):
        self.make_mod(self.root / "Optimus-Forge189-1.jar")
        self.make_mod(self.root / "Optimus-Forge189-2.jar")
        with patch("optimus_headless.cli.PROJECT_ROOT", self.root):
            with self.assertRaisesRegex(ValueError, "explicitly"):
                resolve_mod()

    def test_manifest_pins_release_assets_and_checksums(self):
        manifest = json.loads((Path(__file__).resolve().parents[1] / "dependencies.json").read_text())
        self.assertEqual("11.15.1.2318", manifest["forge"])
        for artifact in [*manifest["launchers"].values(), manifest["specifics"]]:
            self.assertTrue(artifact["url"].startswith("https://github.com/headlesshq/"))
            self.assertNotIn("latest", artifact["url"])
            self.assertRegex(artifact["sha256"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()