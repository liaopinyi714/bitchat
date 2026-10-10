#!/usr/bin/env python3
"""Sign all optimized phone APKs using the maintained release key and public lineage.

JDK 21 and the pinned Android SDK are required. Passwords are read by apksigner
from a local file, never printed or placed in command-line arguments.
The input APKs are not modified. Only a new, empty output directory is accepted.
"""

import argparse
import base64
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("apk_checks", ROOT / "tools/verify-apk.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--alias", default="bitchat-release")
    parser.add_argument("--baseline", type=Path, help="Previous installed universal APK")
    args = parser.parse_args()
    checks.require(args.keystore.is_file() and args.password_file.is_file(), "Local signing files unavailable")
    checks.require(not args.output_dir.exists(), "Choose a new output directory; existing artifacts are never overwritten")
    # Validate the entire unsigned input before asking the key to sign anything.
    checks.run_tool([sys.executable, str(ROOT / "tools/verify-apk.py"), "--apk-dir",
                     str(args.input_dir), "--unsigned"])
    args.output_dir.mkdir(parents=True)
    # Keep temporary public lineage data inside the explicitly selected output root.
    # This also works on restricted hosts where the system temp directory is unavailable.
    with tempfile.TemporaryDirectory(prefix="bitchat-sign-", dir=args.output_dir.resolve()) as temporary:
        checks.require(Path(temporary).resolve().parent == args.output_dir.resolve(), "Invalid temporary signing directory")
        lineage = Path(temporary) / "lineage.bin"
        lineage.write_bytes(base64.b64decode((ROOT / "release/signing-lineage.base64").read_text().strip(), validate=True))
        for abi in ["universal", *sorted(checks.ABIS)]:
            source = args.input_dir / f"app-{abi}-release-unsigned.apk"
            target = args.output_dir / f"app-{abi}-release.apk"
            checks.run_tool([
                checks.sdk_tool("apksigner"), "sign", "--ks", str(args.keystore),
                "--ks-key-alias", args.alias, "--ks-pass", "file:" + str(args.password_file),
                "--lineage", str(lineage), "--rotation-min-sdk-version", "28",
                "--min-sdk-version", "34", "--v1-signing-enabled", "false",
                "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
                "--v4-signing-enabled", "false", "--debuggable-apk-permitted", "false",
                "--out", str(target), str(source),
            ])
        verify = [sys.executable, str(ROOT / "tools/verify-apk.py"), "--apk-dir", str(args.output_dir), "--release"]
        if args.baseline:
            verify += ["--baseline", str(args.baseline)]
        result = subprocess.run(verify, check=False)
        checks.require(result.returncode == 0, "Signed artifacts did not pass release verification")
    print("Release APKs signed and verified. Keep the keystore and password outside the repository.")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        raise SystemExit(f"Release signing failed: {error}")
    except OSError:
        raise SystemExit("Release signing failed; inspect local configuration. Do not publish incomplete output.")
