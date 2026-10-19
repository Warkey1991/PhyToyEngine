"""Opt-in Gradle integration tests, with an isolated fixture and disposable key.

PHYTOY_RUN_GRADLE_PACKAGING_TESTS=1 python3 -m unittest discover \
    -s tools/tests -p test_gradle_packaging.py

Uses cached Gradle offline; does not build Android, read the release key or access a store.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.environ.get("PHYTOY_RUN_GRADLE_PACKAGING_TESTS") == "1",
                     "Set PHYTOY_RUN_GRADLE_PACKAGING_TESTS=1 for offline Gradle integration tests")
class GradlePackagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key_temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.key_temp.cleanup)
        cls.key = Path(cls.key_temp.name) / "fixture key.jks"
        # Literal metacharacters must not be evaluated by a shell or Properties.load.
        cls.password = "fixture_$(never-run)='literal'"
        cls.env = {k: v for k, v in os.environ.items() if not k.startswith("PHYTOY_")}
        cls.env["PKG_FIXTURE_PASSWORD"] = cls.password
        cls.env["JAVA_HOME"] = subprocess.check_output(
            ["/usr/libexec/java_home", "-v", "21"], text=True).strip() if os.name == "posix" and Path("/usr/libexec/java_home").is_file() else os.environ["JAVA_HOME"]
        cls.env["PATH"] = str(Path(cls.env["JAVA_HOME"]) / "bin") + os.pathsep + cls.env.get("PATH", "")
        cls.gradle = os.environ.get("PHYTOY_GRADLE_TEST_BIN", str(ROOT / "android/gradlew"))
        subprocess.run([
            str(Path(cls.env["JAVA_HOME"]) / "bin/keytool"), "-genkeypair", "-keystore", str(cls.key),
            "-storetype", "JKS", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=Disposable packaging fixture",
            "-storepass:env", "PKG_FIXTURE_PASSWORD", "-keypass:env", "PKG_FIXTURE_PASSWORD",
        ], env=cls.env, check=True, capture_output=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.android = self.root / "android"
        self.android.mkdir()
        (self.android / "sample").mkdir()
        (self.root / "tools").mkdir()
        self.config = self.android / "keystore.properties"
        self.config.write_text(f"storeFile={self.key}\nstorePassword={self.password}\n"
                               f"keyAlias=fixture\nkeyPassword={self.password}\n")
        self.config.chmod(0o600)
        (self.android / "settings.gradle.kts").write_text('rootProject.name = "packagingFixture"\ninclude(":sample")\n')
        script_path = json.dumps(str(ROOT / "android/package.gradle.kts"))
        (self.android / "build.gradle.kts").write_text(f"apply(from = {script_path})\n")
        (self.android / "sample/build.gradle.kts").write_text('''
for (channel in listOf("play", "galaxy", "galaxyFull")) {
    val flavor = channel.replaceFirstChar(Char::uppercaseChar)
    val prepare = tasks.register("pre${flavor}ReleaseBuild") {
        doLast { rootDir.resolve("$channel-built").writeText("built") }
    }
    tasks.register("assemble${flavor}Release") {
        dependsOn(prepare)
        doLast {
            for (relative in listOf(
                "outputs/apk/$channel/release/sample-$channel-release.apk",
                "outputs/mapping/${channel}Release/mapping.txt",
                "outputs/native-debug-symbols/${channel}Release/native-debug-symbols.zip"
            )) {
                val target = projectDir.resolve("build/$relative")
                target.parentFile.mkdirs(); target.writeText("fixture-$channel")
            }
        }
    }
    tasks.register("bundle${flavor}Release") {
        dependsOn(prepare)
        doLast {
            val target = projectDir.resolve("build/outputs/bundle/${channel}Release/sample-$channel-release.aab")
            target.parentFile.mkdirs(); target.writeText("bundle-$channel")
        }
    }
    tasks.register("lint${flavor}Release") { dependsOn(prepare) }
}
''')
        shutil.copy2(ROOT / "tools/package_android_release.py", self.root / "tools/package_android_release.py")
        (self.root / "tools/check_android_release.py").write_text('''
import argparse,json,os,sys
from pathlib import Path
p=argparse.ArgumentParser()
for name in ['channel','apk','aab','expected-application-id','expected-cert-sha256','output']:
    p.add_argument('--'+name,required=True)
p.add_argument('--store-ready',action='store_true')
a=p.parse_args()
assert all(not os.environ.get(k) for k in ['PHYTOY_KEYSTORE','PHYTOY_STORE_PASSWORD','PHYTOY_KEY_ALIAS','PHYTOY_KEY_PASSWORD'])
assert len(a.expected_cert_sha256)==64
if os.environ.get('PKG_FIXTURE_AUDIT_FAIL'):
    print('fixture audit rejected');sys.exit(1)
result={'status':'passed','store_ready':a.store_ready,'aab':{'manifest':{'version_name':'fixture','version_code':7}}}
out=Path(a.output);out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(result))
''')

    def run_gradle(self, *tasks, env=None):
        result = subprocess.run([
            self.gradle, "--no-daemon", "--no-configuration-cache", "--offline", "--console=plain",
            "-p", str(self.android), *tasks,
        ], env=self.env | (env or {}), text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=180)
        self.assertNotIn(self.password, result.stdout)
        return result

    def test_both_channels_archive_and_hash_without_exposing_credentials(self):
        result = self.run_gradle("packageAll", "-PpackageStoreReady=true")
        self.assertEqual(result.returncode, 0, result.stdout[-6000:])
        for channel in ["play", "galaxy"]:
            folder = self.root / f"release-artifacts/packages/{channel}/fixture-7-store-checked"
            self.assertTrue((self.android / f"{channel}-built").is_file())
            audit = json.loads((folder / "packaging-audit.json").read_text())
            self.assertTrue(audit["store_ready"])
            for line in (folder / "SHA256SUMS.txt").read_text().splitlines():
                digest, filename = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256((folder / filename).read_bytes()).hexdigest())

    def test_single_channel_and_custom_output(self):
        output = self.root / "output with spaces"
        result = self.run_gradle("packagePlay", f"-PpackageOutputDir={output}")
        self.assertEqual(result.returncode, 0, result.stdout[-6000:])
        self.assertTrue((output / "play/fixture-7-candidate").is_dir())
        self.assertFalse((self.android / "galaxy-built").exists())

    def test_full_edition_packages_independently(self):
        result = self.run_gradle("packageGalaxyFull", "-PpackageStoreReady=true")
        self.assertEqual(result.returncode, 0, result.stdout[-6000:])
        folder = self.root / "release-artifacts/packages/galaxyFull/fixture-7-store-checked"
        self.assertTrue((folder / "PomiCamera-galaxyFull-fixture-7.apk").is_file())
        self.assertFalse((self.android / "galaxy-built").exists())
        self.assertFalse((self.android / "play-built").exists())

    def test_audit_failure_does_not_archive(self):
        result = self.run_gradle("packagePlay", env={"PKG_FIXTURE_AUDIT_FAIL": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture audit rejected", result.stdout)
        self.assertFalse((self.root / "release-artifacts/packages").exists())

    def test_incomplete_environment_is_rejected(self):
        result = self.run_gradle("packagePlay", env={"PHYTOY_KEY_ALIAS": "incomplete"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("all four signing environment variables", result.stdout)
        self.assertFalse((self.android / "play-built").exists())

    def test_complete_environment_works_without_local_config(self):
        self.config.unlink()
        result = self.run_gradle("checkReleaseSigning", env={
            "PHYTOY_KEYSTORE": str(self.key), "PHYTOY_STORE_PASSWORD": self.password,
            "PHYTOY_KEY_ALIAS": "fixture", "PHYTOY_KEY_PASSWORD": self.password,
        })
        self.assertEqual(result.returncode, 0, result.stdout[-6000:])
        self.assertIn("Signing key validated", result.stdout)

    def test_configuration_cache_is_rejected(self):
        result = self.run_gradle("packagePlay", "--configuration-cache")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("run with --no-configuration-cache", result.stdout)
        self.assertFalse((self.android / "play-built").exists())

    @unittest.skipUnless(os.name == "posix", "POSIX file permissions")
    def test_publicly_readable_config_is_rejected(self):
        self.config.chmod(0o644)
        result = self.run_gradle("packagePlay")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Signing config must be private", result.stdout)

    def test_duplicate_fields_are_rejected(self):
        with self.config.open("a") as file:
            file.write("keyPassword=" + self.password + "\n")
        result = self.run_gradle("packagePlay")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("one unique key=value", result.stdout)

    def test_invalid_password_stops_before_app_build(self):
        self.config.write_text(self.config.read_text().replace(
            "keyPassword=" + self.password, "keyPassword=incorrect-fixture-password"))
        result = self.run_gradle("packagePlay")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.android / "play-built").exists())

    def test_help_does_not_require_signing_config(self):
        self.config.unlink()
        result = self.run_gradle("help")
        self.assertEqual(result.returncode, 0, result.stdout[-6000:])


if __name__ == "__main__":
    unittest.main()
