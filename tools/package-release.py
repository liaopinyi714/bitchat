#!/usr/bin/env python3
"""Prepare the four public release assets from verified APKs and a clean Git commit."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

from build_provenance import ASSET as PROVENANCE_ASSET, fingerprint_scope, require_committed_source_inputs, source_provenance
from release_output import atomic_output_directory

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("apk_checks", ROOT / "tools/verify-apk.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True, stderr=subprocess.DEVNULL).strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    checks.require(not git("status", "--porcelain"), "Commit and review source before preparing release metadata")
    checks.require(not args.output_dir.exists(), "Choose a new release directory")
    commit, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
    require_committed_source_inputs(ROOT, commit)
    verified = json.loads(checks.run_tool([
        sys.executable, str(ROOT / "tools/verify-apk.py"), "--apk-dir", str(args.signed_dir), "--release"
    ]))
    fingerprint = verified["sourceFingerprint"]
    source = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName = "([^"]+)"', source).group(1)
    code = int(re.search(r"versionCode = (\d+)", source).group(1))
    versions = (ROOT / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
    value = lambda key: re.search(rf'^{key} = "([^"]+)"', versions, re.M).group(1)
    wrapper = (ROOT / "android/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    gradle = re.search(r"gradle-([\d.]+)-bin.zip", wrapper).group(1)
    with atomic_output_directory(args.output_dir) as staging:
        apks = []
        for abi in ["arm64-v8a", "universal"]:
            target = staging / f"bitchat-android-{abi}.apk"
            shutil.copyfile(args.signed_dir / f"app-{abi}-release.apk", target)
            # Check the copied APK, so a stale/different input cannot silently be
            # substituted between initial verification and asset creation.
            with checks.zipfile.ZipFile(target) as archive:
                checks.require_provenance(archive, fingerprint)
            digest = sha256(target)
            checks.require(digest == verified["outputs"][abi]["apkSha256"], "Copied APK differs from the verified artifact")
            apks.append({"file": target.name, "bytes": target.stat().st_size, "sha256": digest})
        metadata = {
            "applicationId": "xyz.liaopinyi714.bitchat", "versionName": version, "versionCode": code,
            "sourceRepository": "https://github.com/liaopinyi714/bitchat",
            "sourceCommit": commit, "sourceTree": tree,
            "sourceFingerprint": {**fingerprint, "asset": PROVENANCE_ASSET, "scope": fingerprint_scope()},
            "tag": "v" + version, "minSdk": int(value("minSdk")), "targetSdk": int(value("targetSdk")),
            "toolchain": {"jdkMajor": 21, "gradle": gradle, "agp": value("agp"), "kotlin": value("kotlin"),
                          "buildTools": value("buildTools"), "compileSdk": int(value("compileSdk"))},
            "signingCertificateSha256": checks.release_fingerprint(), "apkSignatureScheme": "v3",
            "minified": True, "resourcesShrunk": True, "independentlyReproduced": False,
            "physicalDeviceAcceptance": "pending", "artifacts": apks,
        }
        info = staging / "BUILDINFO.json"
        info.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8", newline="\n")
        entries = [f"{item['sha256']}  {item['file']}" for item in apks]
        entries.append(f"{sha256(info)}  {info.name}")
        (staging / "SHA256SUMS").write_text("\n".join(entries) + "\n", encoding="utf-8", newline="\n")
        checks.require(source_provenance(ROOT) == fingerprint, "Source inputs changed during release packaging")
        require_committed_source_inputs(ROOT, commit)
        checks.require(not git("status", "--porcelain") and git("rev-parse", "HEAD") == commit,
                       "Source checkout changed during release packaging")
    print("Prepared two signed APKs, BUILDINFO.json and SHA256SUMS. No private signing files included.")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        raise SystemExit(f"Release packaging failed: {error}")
    except (OSError, subprocess.CalledProcessError, checks.zipfile.BadZipFile, KeyError):
        raise SystemExit("Release packaging failed; inspect local configuration. Do not publish incomplete output.")
