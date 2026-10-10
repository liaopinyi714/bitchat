#!/usr/bin/env python3
"""Check optimized phone APKs without disclosing paths or certificate metadata.

Uses only Python's standard library and the pinned Android SDK's aapt2/apksigner.
This checks packaging and reflection contracts, not real-device behavior.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
NATIVE = {"libarti_android.so", "libbarhopper_v3.so"}
MODELS = {
    "Lcom/bitchat/android/services/SeenMessageStore$StorePayload;":
        {"delivered", "locallyRead", "readReceiptsSent"},
    "Lcom/bitchat/android/geohash/LocationChannelManager$PersistedChannel;":
        {"mesh", "level", "geohash", "source"},
    "Lcom/bitchat/android/favorites/FavoriteRelationshipData;":
        {"peerNoisePublicKeyHex", "peerNostrPublicKey", "peerNickname", "isFavorite",
         "theyFavoritedUs", "favoritedAt", "lastUpdated"},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid DEX integer")


def dex_members(data):
    """Read declared field/method names, including constructors, from DEX class data."""
    require(data[:4] == b"dex\n", "Unrecognized DEX format")
    word = lambda offset: struct.unpack_from("<I", data, offset)[0]
    require(word(32) == len(data) and word(40) == 0x12345678, "Invalid DEX header")
    strings = []
    for i in range(word(56)):
        offset = word(word(60) + i * 4)
        _, offset = uleb(data, offset)
        end = data.index(0, offset)
        # Only ASCII class/member names are used here; MUTF-8 text is not interpreted.
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[word(word(68) + i * 4)] for i in range(word(64))]
    field_names = [strings[word(word(84) + i * 8 + 4)] for i in range(word(80))]
    method_names = [strings[word(word(92) + i * 8 + 4)] for i in range(word(88))]
    result = {}
    for i in range(word(96)):
        start = word(100) + i * 32
        name = types[word(start)]
        offset = word(start + 24)
        fields, methods = set(), set()
        if offset:
            counts = []
            for _ in range(4):
                count, offset = uleb(data, offset)
                counts.append(count)
            for count, names, destination in (
                (counts[0], field_names, fields), (counts[1], field_names, fields),
                (counts[2], method_names, methods), (counts[3], method_names, methods),
            ):
                index = 0
                for _ in range(count):
                    delta, offset = uleb(data, offset)
                    index += delta
                    _, offset = uleb(data, offset)  # access flags
                    if destination is methods:
                        _, offset = uleb(data, offset)  # code offset
                    destination.add(names[index])
        result[name] = (fields, methods)
    return result


def sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK")
    suffix = ".bat" if name == "apksigner" and os.name == "nt" else (
        ".exe" if os.name == "nt" else "")
    tool = Path(sdk) / "build-tools" / "37.0.0" / (name + suffix)
    require(tool.is_file(), "Pinned Android build tool unavailable")
    return str(tool)


def run_tool(args):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", check=False)
    require(result.returncode == 0, "Android SDK artifact check failed")
    return result.stdout


def check_apk(path, expected_abis, signed):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "Corrupt APK ZIP entry")
        names = set(archive.namelist())
        abis = {n.split("/")[1] for n in names if n.startswith("lib/")}
        require(abis == expected_abis, "Native ABI coverage mismatch")
        for abi in abis:
            for library in NATIVE:
                require(f"lib/{abi}/{library}" in names, "Missing Tor/offline barcode library")
        classes = {}
        dex_bytes = 0
        for name in sorted(names):
            if re.fullmatch(r"classes\d*\.dex", name):
                content = archive.read(name)
                dex_bytes += len(content)
                classes.update(dex_members(content))
        for model, fields in MODELS.items():
            require(model in classes, "Missing persisted JSON model")
            kept_fields, methods = classes[model]
            require(fields <= kept_fields and "<init>" in methods,
                    "Persisted JSON fields/constructor changed by shrinking")
        for cls, methods in {
            "Lorg/torproject/arti/ArtiNative;":
                {"getVersion", "setLogCallback", "initialize", "startSocksProxy", "stop"},
            "Linfo/guardianproject/arti/ArtiLogListener;": {"onLogLine"},
            "Landroidx/work/impl/WorkDatabase_Impl;": {"<init>"},
        }.items():
            require(cls in classes and methods <= classes[cls][1],
                    "JNI/WorkManager reflection entry point missing")
        require(not any("/testhook/" in cls for cls in classes), "ADB test hooks in optimized APK")
        model_assets = [n for n in names if n.startswith("assets/mlkit_barcode_models/")]
        require(len(model_assets) >= 3, "Offline barcode models missing")
        contents = {hashlib.sha256(archive.read(n)).digest() for n in names if n.startswith("res/")}
        fonts = list((ROOT / "android/app/src/main/res/font").glob("*.ttf"))
        require(len(fonts) == 4, "Expected original UI fonts unavailable")
        for font in fonts:
            require(hashlib.sha256(font.read_bytes()).digest() in contents, "Original UI font missing")
        core_hashes = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in names
                       if re.fullmatch(r"classes\d*\.dex", n) or n == "resources.arsc"
                       or n.startswith("assets/")}

    badging = run_tool([sdk_tool("aapt2"), "dump", "badging", str(path)])
    require("name='xyz.liaopinyi714.bitchat'" in badging, "Wrong application ID")
    require("versionCode='7'" in badging and "versionName='0.1.6'" in badging, "Wrong version")
    require(re.search(r"(?:sdkVersion|minSdkVersion):'34'", badging), "Wrong minimum Android SDK")
    require("targetSdkVersion:'37'" in badging, "Wrong target Android SDK")
    require("application-debuggable" not in badging, "Optimized APK is debuggable")
    require(not any(p in badging for p in (
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION")), "Location permission in merged APK")
    configurations = run_tool([sdk_tool("aapt2"), "dump", "configurations", str(path)]).splitlines()
    for locale in ET.parse(ROOT / "android/app/src/main/res/xml/locales_config.xml").getroot():
        tag = locale.attrib["{http://schemas.android.com/apk/res/android}name"]
        language, _, region = tag.partition("-")
        qualifier = language + (f"-r{region}" if region else "")
        require(any(c == qualifier or c.startswith(qualifier + "-") for c in configurations),
                "Declared application language missing from packaged resources")
    run_tool([sdk_tool("zipalign"), "-c", "-P", "16", "4", str(path)])
    certificate = None
    if signed:
        signing = run_tool([sdk_tool("apksigner"), "verify", "--print-certs", str(path)])
        match = re.search(r"certificate SHA-256 digest: ([a-fA-F0-9]+)", signing)
        require(match, "Signature certificate unavailable")
        certificate = match.group(1).lower()
    return core_hashes, certificate, {"abis": sorted(abis), "apkBytes": path.stat().st_size,
                                     "expandedDexBytes": dex_bytes}


def compare_baseline(baseline, optimized):
    """Confirm every packaged native library and static asset retains the same bytes."""
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(optimized) as new:
        for name in old.namelist():
            if name.startswith(("lib/", "assets/", "org/bouncycastle/")):
                require(name in new.namelist() and old.read(name) == new.read(name),
                        "Existing native library/static asset changed")
    return baseline.stat().st_size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--unsigned", action="store_true", help="Check ordinary unsigned release output")
    parser.add_argument("--baseline", type=Path, help="Previous universal APK, kept local")
    args = parser.parse_args()
    suffix = "-unsigned" if args.unsigned else ""
    outputs = {}
    reference_hashes, reference_certificate = None, None
    universal = args.apk_dir / f"app-universal-release{suffix}.apk"
    for abi in ["universal", *sorted(ABIS)]:
        apk = args.apk_dir / f"app-{abi}-release{suffix}.apk"
        require(apk.is_file(), "Expected optimized APK missing")
        hashes, certificate, size = check_apk(apk, ABIS if abi == "universal" else {abi}, not args.unsigned)
        if reference_hashes is None:
            reference_hashes, reference_certificate = hashes, certificate
        require(hashes == reference_hashes, "ABI APKs contain different code/resources/assets")
        require(certificate == reference_certificate, "ABI APK signatures differ")
        outputs[abi] = size
    if args.baseline:
        old_bytes = compare_baseline(args.baseline, universal)
        if not args.unsigned:
            signing = run_tool([sdk_tool("apksigner"), "verify", "--print-certs", str(args.baseline)])
            digest = re.search(r"certificate SHA-256 digest: ([a-fA-F0-9]+)", signing)
            require(digest and digest.group(1).lower() == reference_certificate,
                    "Preview cannot update the previous development installation")
        for size in outputs.values():
            size["reductionPercentVsBaselineUniversal"] = round((1 - size["apkBytes"] / old_bytes) * 100, 1)
        outputs["baselineUniversalBytes"] = old_bytes
    print(json.dumps({"checks": "passed", "outputs": outputs}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        raise SystemExit(f"APK verification failed: {error}")
    except (OSError, IndexError, struct.error, zipfile.BadZipFile):
        # Do not disclose raw subprocess output, local paths or signature metadata.
        raise SystemExit("APK verification failed; inspect the build locally")
