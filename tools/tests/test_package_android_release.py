"""Credential-handling boundaries; no real signing keys or network access."""
from pathlib import Path
import importlib.util
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("package_android_release", Path(__file__).parents[1] / "package_android_release.py")
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class SigningConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.keystore = self.root / "test key.jks"
        self.keystore.write_bytes(b"test fixture, not a private key")
        self.config = self.root / "keystore.properties"
        self.password = "fixture&value_$(never-run)='literal'"
        self.config.write_text(f"storeFile={self.keystore}\nstorePassword={self.password}\n"
                               f"keyAlias=Test\nkeyPassword={self.password}\n")
        self.config.chmod(0o600)
        self.env = {"JAVA_HOME": str(self.root), "PATH": ""}

    def test_password_is_literal_data_and_is_redacted_from_output(self):
        env = packaging.signing_environment(self.config, self.env)
        self.assertEqual(env["PHYTOY_STORE_PASSWORD"], self.password)
        self.assertEqual(env["PHYTOY_KEY_PASSWORD"], self.password)
        self.assertEqual(packaging.redact("Failure: " + self.password, env), "Failure: <redacted>")

    def test_partial_environment_cannot_mix_with_local_credentials(self):
        with self.assertRaisesRegex(packaging.PackageError, "all four"):
            packaging.signing_environment(self.config, self.env | {"PHYTOY_KEY_ALIAS": "DifferentKey"})

    def test_complete_environment_works_without_reading_local_file(self):
        env = self.env | {"PHYTOY_KEYSTORE": str(self.keystore), "PHYTOY_STORE_PASSWORD": "env-password",
                          "PHYTOY_KEY_ALIAS": "EnvironmentKey", "PHYTOY_KEY_PASSWORD": "env-key-password"}
        self.config.unlink()
        result = packaging.signing_environment(self.config, env)
        self.assertEqual(result["PHYTOY_KEY_ALIAS"], "EnvironmentKey")
        self.assertEqual(result["PHYTOY_STORE_PASSWORD"], "env-password")

    def test_shared_read_permissions_are_rejected(self):
        self.config.chmod(0o644)
        with self.assertRaisesRegex(packaging.PackageError, "private permissions"):
            packaging.signing_environment(self.config, self.env)

    def test_duplicate_fields_fail_without_printing_the_secret_line(self):
        with self.config.open("a") as file:
            file.write("keyPassword=" + self.password + "\n")
        with self.assertRaises(packaging.PackageError) as caught:
            packaging.signing_environment(self.config, self.env)
        self.assertNotIn(self.password, str(caught.exception))


if __name__ == "__main__":
    unittest.main()
