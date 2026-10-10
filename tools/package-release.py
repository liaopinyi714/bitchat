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

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("apk_checks", ROOT / "tools/verify-apk.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True, stderr=subprocess.DEVNULL).strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    checks.require(not git("status", "--porcelain"), "Commit and review source before preparing release metadata")
    checks.require(not args.output_dir.exists(), "Choose a new release directory")
    checks.run_tool([sys.executable, str(ROOT / "tools/verify-apk.py"), "--apk-dir", str(args.signed_dir), "--release"])
    source = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName = "([^"]+)"', source).group(1)
    code = int(re.search(r"versionCode = (\d+)", source).group(1))
    versions = (ROOT / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
    value = lambda key: re.search(rf'^{key} = "([^"]+)"', versions, re.M).group(1)
    wrapper = (ROOT / "android/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    gradle = re.search(r"gradle-([\d.]+)-bin.zip", wrapper).group(1)
    args.output_dir.mkdir(parents=True)
    apks = []
    for abi in ["arm64-v8a", "universal"]:
        target = args.output_dir / f"bitchat-android-{abi}.apk"
        shutil.copyfile(args.signed_dir / f"app-{abi}-release.apk", target)
        apks.append({"file": target.name, "bytes": target.stat().st_size, "sha256": sha256(target)})
    metadata = {
        "applicationId": "xyz.liaopinyi714.bitchat", "versionName": version, "versionCode": code,
        "sourceRepository": "https://github.com/liaopinyi714/bitchat",
        "sourceCommit": git("rev-parse", "HEAD"), "sourceTree": git("rev-parse", "HEAD^{tree}"),
        "tag": "v" + version, "minSdk": int(value("minSdk")), "targetSdk": int(value("targetSdk")),
        "toolchain": {"jdkMajor": 21, "gradle": gradle, "agp": value("agp"), "kotlin": value("kotlin"),
                      "buildTools": value("buildTools"), "compileSdk": int(value("compileSdk"))},
        "signingCertificateSha256": checks.release_fingerprint(), "apkSignatureScheme": "v3",
        "minified": True, "resourcesShrunk": True, "independentlyReproduced": False,
        "physicalDeviceAcceptance": "pending", "artifacts": apks,
    }
    info = args.output_dir / "BUILDINFO.json"
    info.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8", newline="\n")
    entries = [f"{item['sha256']}  {item['file']}" for item in apks]
    entries.append(f"{sha256(info)}  {info.name}")
    (args.output_dir / "SHA256SUMS").write_text("\n".join(entries) + "\n", encoding="utf-8", newline="\n")
    print("Prepared two signed APKs, BUILDINFO.json and SHA256SUMS. No private signing files included.")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        raise SystemExit(f"Release packaging failed: {error}")
    except (OSError, subprocess.CalledProcessError):
        raise SystemExit("Release packaging failed; inspect local configuration. Do not publish incomplete output.")
