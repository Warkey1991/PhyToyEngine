#!/usr/bin/env python3
"""Build and audit signed Android packages using local, ignored credentials."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SIGNING_FIELDS = {
    "storeFile": "PHYTOY_KEYSTORE",
    "storePassword": "PHYTOY_STORE_PASSWORD",
    "keyAlias": "PHYTOY_KEY_ALIAS",
    "keyPassword": "PHYTOY_KEY_PASSWORD",
}
APPLICATION_IDS = {
    "play": "com.ycolor.team.phytoy.camera.android.gpapp",
    "galaxy": "com.ycolor.team.phytoy.camera.android.galaxyapp",
}


class PackageError(ValueError):
    pass


def signing_environment(config: Path, inherited: dict[str, str]) -> dict[str, str]:
    env = inherited.copy()
    present = [name for name in SIGNING_FIELDS.values() if env.get(name)]
    if present:
        if len(present) != len(SIGNING_FIELDS):
            raise PackageError("Supply all four signing environment variables, or none to use the local config.")
    else:
        if not config.is_file():
            raise PackageError("Missing local signing config; copy android/keystore.properties.example to android/keystore.properties.")
        if os.name == "posix" and stat.S_IMODE(config.stat().st_mode) & 0o077:
            raise PackageError("Local signing config must have private permissions: chmod 600 android/keystore.properties")
        values: dict[str, str] = {}
        for line in config.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith(("#", "!")):
                continue
            key, separator, value = line.partition("=")
            key = key.strip()
            if not separator or key not in SIGNING_FIELDS or key in values:
                raise PackageError("Signing config requires one unique key=value line for each documented field.")
            # Read passwords literally. Never source this file as shell code.
            values[key] = value
        if any(not values.get(key) for key in SIGNING_FIELDS):
            raise PackageError("Signing config is missing a required field.")
        env.update({variable: values[key] for key, variable in SIGNING_FIELDS.items()})
    keystore = Path(env["PHYTOY_KEYSTORE"]).expanduser()
    if not keystore.is_absolute() or not keystore.is_file():
        raise PackageError("Keystore must be an existing file specified by an absolute path.")
    env["PHYTOY_KEYSTORE"] = str(keystore)
    if not env.get("JAVA_HOME") and sys.platform == "darwin":
        result = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True)
        if result.returncode != 0:
            raise PackageError("Install JDK 21 or set JAVA_HOME to the project's JDK.")
        env["JAVA_HOME"] = result.stdout.strip()
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    return env


def redact(value: str, env: dict[str, str]) -> str:
    passwords = {env[name] for name in ("PHYTOY_STORE_PASSWORD", "PHYTOY_KEY_PASSWORD") if env.get(name)}
    for password in sorted(passwords, key=len, reverse=True):
        value = value.replace(password, "<redacted>")
    return value


def signing_certificate(env: dict[str, str]) -> str:
    keytool = shutil.which("keytool", path=env.get("PATH"))
    if not keytool:
        raise PackageError("JDK keytool is unavailable.")
    common = [keytool, "-keystore", env["PHYTOY_KEYSTORE"], "-alias", env["PHYTOY_KEY_ALIAS"],
              "-storepass:env", "PHYTOY_STORE_PASSWORD"]
    # Creating an in-memory CSR also verifies that the private-key password works;
    # listing a certificate alone would only verify the keystore password.
    result = subprocess.run(common + ["-certreq", "-keypass:env", "PHYTOY_KEY_PASSWORD"],
                            env=env, capture_output=True)
    if result.returncode != 0:
        details = (result.stderr or result.stdout).decode("utf-8", errors="replace")
        raise PackageError("Keystore/private-key validation failed: " + redact(details.strip(), env))
    result = subprocess.run(common + ["-exportcert"], env=env, capture_output=True)
    if result.returncode != 0 or not result.stdout:
        raise PackageError("Could not read the signing certificate.")
    return hashlib.sha256(result.stdout).hexdigest()


def run_visible(command: list[str], env: dict[str, str], cwd: Path) -> None:
    with subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as process:
        assert process.stdout is not None
        for line in process.stdout:
            print(redact(line, env), end="", flush=True)
        if process.wait() != 0:
            raise PackageError(f"{Path(command[0]).name} failed; see the output above.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=("play", "galaxy", "both"), default="both")
    parser.add_argument("--check-signing", action="store_true", help="Validate the keystore, alias and both passwords without building.")
    parser.add_argument("--store-ready", action="store_true", help="Also enforce existing publisher and billing configuration gates.")
    parser.add_argument("--offline", action="store_true", help="Use only cached Gradle dependencies.")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    args = parser.parse_args()
    try:
        if args.version_code is not None and not 1 <= args.version_code <= 2100000000:
            raise PackageError("Version code must be a positive Android-compatible integer.")
        if args.version_name is not None and not args.version_name.strip():
            raise PackageError("Version name must not be blank.")
        env = signing_environment(ROOT / "android/keystore.properties", dict(os.environ))
        certificate = signing_certificate(env)
        print("Signing key validated. Certificate SHA-256: " + certificate, flush=True)
        if args.check_signing:
            return 0
        channels = ("play", "galaxy") if args.channel == "both" else (args.channel,)
        command = [str(ROOT / "android/gradlew"), "--no-daemon", "--no-configuration-cache", "--console=plain",
                   "-PphytoyRequireSignedRelease=true"]
        if args.offline:
            command.append("--offline")
        if args.store_ready:
            command.extend(["-PphytoyRequireBillingConfigured=true", "-PphytoyRequirePublisherConfigured=true"])
        if args.version_code is not None:
            command.append(f"-PphytoyVersionCode={args.version_code}")
        if args.version_name is not None:
            command.append("-PphytoyVersionName=" + args.version_name)
        for channel in channels:
            flavor = channel.title()
            command.extend([f":sample:assemble{flavor}Release", f":sample:bundle{flavor}Release",
                            f":sample:lint{flavor}Release"])
        print("Building signed release packages for: " + ", ".join(channels), flush=True)
        run_visible(command, env, ROOT / "android")
        audit_env = {key: value for key, value in env.items() if key not in SIGNING_FIELDS.values()}
        for channel in channels:
            apk = ROOT / f"android/sample/build/outputs/apk/{channel}/release/sample-{channel}-release.apk"
            aab = ROOT / f"android/sample/build/outputs/bundle/{channel}Release/sample-{channel}-release.aab"
            report = ROOT / f"release-artifacts/{channel}-packaging.json"
            command = [sys.executable, str(ROOT / "tools/check_android_release.py"), "--channel", channel,
                       "--apk", str(apk), "--aab", str(aab), "--expected-application-id", APPLICATION_IDS[channel],
                       "--expected-cert-sha256", certificate, "--output", str(report)]
            if args.store_ready:
                command.append("--store-ready")
            result = subprocess.run(command, cwd=ROOT, env=audit_env, capture_output=True, text=True)
            if result.returncode != 0:
                print(redact(result.stdout + result.stderr, env), end="", file=sys.stderr)
                raise PackageError("Signed artifact audit failed for " + channel)
            print(f"{channel}: signed APK/AAB and certificate audit passed.\nAPK: {apk}\nAAB: {aab}\nAudit: {report}")
        return 0
    except (PackageError, OSError) as error:
        print("Packaging failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
