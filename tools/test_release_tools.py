"""Release-gate regressions using synthetic files only; no Android SDK or signing key.

Run: python3 -m unittest discover -s tools -p 'test_*.py'
"""

from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from build_provenance import ASSET, INPUT_PATTERNS, require_committed_source_inputs, source_input_paths, source_provenance
from release_output import atomic_output_directory

TOOLS = Path(__file__).resolve().parent


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), TOOLS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


checks = load_tool("verify-apk")
signing = load_tool("sign-release")


def write(root, relative, content):
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
    return target


def source_fixture(root):
    write(root, "android/app/build.gradle.kts", b"// synthetic build\n")
    write(root, "android/app/src/main/AndroidManifest.xml", b"<manifest/>\n")
    write(root, "android/app/src/main/java/Example.kt", b"class Example\n")


def archive(contents):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for name, content in contents.items():
            output.writestr(name, content)
    return zipfile.ZipFile(buffer)


class SourceFingerprintTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        source_fixture(self.root)

    def test_uncommitted_main_and_release_files_change_the_fingerprint(self):
        before = source_provenance(self.root)
        main = write(self.root, "android/app/src/main/java/New.kt", b"class New\n")
        after_main = source_provenance(self.root)
        self.assertNotEqual(before["sourceSha256"], after_main["sourceSha256"])
        self.assertEqual(before["fileCount"] + 1, after_main["fileCount"])
        write(self.root, "android/app/src/release/java/Release.kt", b"class Release\n")
        self.assertNotEqual(after_main, source_provenance(self.root))
        main.unlink()
        self.assertNotEqual(after_main, source_provenance(self.root))

    def test_text_newlines_are_normalized_but_binary_bytes_are_not(self):
        source = self.root / "android/app/src/main/java/Example.kt"
        source.write_bytes(b"class Example\n// value\n")
        expected = source_provenance(self.root)
        for newline in (b"\r\n", b"\r"):
            source.write_bytes(b"class Example" + newline + b"// value" + newline)
            self.assertEqual(expected, source_provenance(self.root))
        native = write(self.root, "android/app/src/main/jniLibs/arm64-v8a/synthetic.so", b"binary\r\n")
        expected = source_provenance(self.root)
        native.write_bytes(b"binary\n")
        self.assertNotEqual(expected, source_provenance(self.root))

    def test_docs_tests_build_outputs_and_local_properties_are_excluded(self):
        expected = source_provenance(self.root)
        for path in (
            "docs/guide.md", "android/app/src/test/java/ExampleTest.kt",
            "android/app/build/generated/source.kt", "android/local.properties",
            "android/.gradle/state.properties",
        ):
            write(self.root, path, b"synthetic local-only contents\n")
        self.assertEqual(expected, source_provenance(self.root))

    def test_a_source_package_named_build_is_still_an_input(self):
        previous = source_provenance(self.root)
        write(self.root, "android/app/src/main/java/example/build/Example.kt", b"class BuildExample\n")
        self.assertNotEqual(previous, source_provenance(self.root))

    def test_dependency_locks_proguard_and_gradle_inputs_are_bound(self):
        for path in (
            "android/app/gradle.lockfile", "android/app/proguard-rules.pro",
            "android/gradle/libs.versions.toml", "android/settings-gradle.lockfile",
            "android/gradle/verification-metadata.xml", "android/wear/build.gradle.kts",
        ):
            previous = source_provenance(self.root)
            write(self.root, path, b"synthetic build input\n")
            self.assertNotEqual(previous, source_provenance(self.root))

    def test_fingerprint_does_not_include_checkout_location(self):
        with tempfile.TemporaryDirectory() as other:
            source_fixture(Path(other))
            self.assertEqual(source_provenance(self.root), source_provenance(Path(other)))

    def test_gradle_and_python_input_patterns_remain_in_sync(self):
        script = (TOOLS.parent / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        task = script.split('val generateReleaseBuildProvenance = tasks.register', 1)[1]
        includes = task.split("include(", 1)[1].split(")", 1)[0]
        self.assertEqual(set(INPUT_PATTERNS), set(re.findall(r'"([^"]+)"', includes)))


class ArtifactChecksTests(unittest.TestCase):
    def test_missing_and_mismatched_source_provenance_are_rejected(self):
        expected = {"schema": 1, "algorithm": "bitchat-apk-source-v1", "sourceSha256": "a" * 64, "fileCount": 3}
        with archive({"assets/example": b"synthetic"}) as apk:
            with self.assertRaisesRegex(ValueError, "provenance missing"):
                checks.require_provenance(apk, expected)
        with archive({ASSET: json.dumps({**expected, "sourceSha256": "b" * 64})}) as apk:
            with self.assertRaisesRegex(ValueError, "fingerprint differs"):
                checks.require_provenance(apk, expected)
        with archive({ASSET: json.dumps(expected)}) as apk:
            self.assertEqual(expected, checks.require_provenance(apk, expected))

    def test_upgrade_requires_matching_application_and_strictly_greater_version(self):
        previous = {"applicationId": "example.synthetic", "versionCode": 11}
        checks.require_upgrade(previous, {**previous, "versionCode": 12})
        for candidate in (previous, {**previous, "versionCode": 10},
                          {"applicationId": "example.other", "versionCode": 12}):
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                checks.require_upgrade(previous, candidate)

    def test_baseline_major_version_prevents_a_hidden_downgrade(self):
        baseline = checks.package_metadata("package: name='example.synthetic' versionCode='1' versionCodeMajor='1' versionName='one'\n")
        candidate = checks.package_metadata("package: name='example.synthetic' versionCode='12' versionName='two'\n")
        with self.assertRaisesRegex(ValueError, "versionCode must increase"):
            checks.require_upgrade(baseline, candidate)

    def test_abi_resources_must_match_the_universal_files(self):
        common = {"res/drawable/example.xml": "original", "resources.arsc": "table", "classes.dex": "code"}
        with self.assertRaisesRegex(ValueError, "different code/resources/assets"):
            checks.require_matching_payloads(common, {}, {**common, "res/drawable/example.xml": "different"}, {}, {"arm64-v8a"})

    def test_abi_native_payloads_must_match_the_corresponding_universal_abi(self):
        native = {"lib/arm64-v8a/example.so": "arm", "lib/x86_64/example.so": "x86"}
        checks.require_matching_payloads({}, native, {}, {"lib/arm64-v8a/example.so": "arm"}, {"arm64-v8a"})
        for candidate in ({"lib/arm64-v8a/example.so": "different"}, {}, native):
            with self.subTest(candidate=candidate), self.assertRaisesRegex(ValueError, "native libraries differ"):
                checks.require_matching_payloads({}, native, {}, candidate, {"arm64-v8a"})

    def test_payload_hashes_include_actual_resources_and_native_bytes(self):
        with archive({"classes.dex": b"code", "resources.arsc": b"table",
                      "res/drawable/example.xml": b"image", "assets/example": b"asset",
                      "lib/arm64-v8a/example.so": b"native"}) as apk:
            common, native = checks.payload_hashes(apk)
        self.assertEqual(set(common), {"classes.dex", "resources.arsc", "res/drawable/example.xml", "assets/example"})
        self.assertEqual(set(native), {"lib/arm64-v8a/example.so"})

    def test_baseline_exclusions_do_not_allow_other_static_assets_to_change(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old, new = root / "old.apk", root / "new.apk"
            with zipfile.ZipFile(old, "w") as apk:
                apk.writestr(ASSET, "old fingerprint")
                apk.writestr("assets/dexopt/baseline.prof", b"old profile")
                apk.writestr("assets/example", b"retained")
            def candidate(asset):
                with zipfile.ZipFile(new, "w") as apk:
                    apk.writestr(ASSET, "new fingerprint")
                    apk.writestr("assets/dexopt/baseline.prof", b"new profile")
                    apk.writestr("assets/example", asset)
            candidate(b"retained")
            checks.compare_baseline(old, new)
            candidate(b"changed")
            with self.assertRaisesRegex(ValueError, "static asset changed"):
                checks.compare_baseline(old, new)


class CommittedSourceTests(unittest.TestCase):
    """Create synthetic Git trees directly; no commits, author identity or credentials."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        isolated_config = patch.dict(os.environ, {"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull})
        isolated_config.start()
        self.addCleanup(isolated_config.stop)
        self.git("init", "--quiet")
        source_fixture(self.root)
        write(self.root, "android/.gitignore", b"**/build/\n")
        # Populate the index from synthetic blobs and write a tree object without
        # creating a commit or configuring any local/global Git identity.
        for relative in [*source_input_paths(self.root), "android/.gitignore"]:
            blob = self.git("hash-object", "-w", "--stdin", data=(self.root / relative).read_bytes()).decode().strip()
            self.git("update-index", "--add", "--cacheinfo", "100644", blob, relative)
        self.tree = self.git("write-tree").decode().strip()

    def git(self, *arguments, data=None):
        return subprocess.check_output(["git", "-C", str(self.root), *arguments], input=data, stderr=subprocess.DEVNULL)

    def test_tracked_tree_matches_portable_newlines_and_ignores_non_inputs(self):
        source = self.root / "android/app/src/main/java/Example.kt"
        source.write_bytes(b"class Example\r\n")
        write(self.root, "docs/untracked.md", b"untracked documentation")
        require_committed_source_inputs(self.root, self.tree)

    def test_ignored_and_untracked_release_inputs_are_rejected(self):
        for relative in ("android/app/src/main/java/example/build/Hidden.kt",
                         "android/app/src/release/java/New.kt"):
            with self.subTest(relative=relative):
                source = write(self.root, relative, b"class NewInput\n")
                if "/build/" in relative:
                    self.git("check-ignore", "--quiet", relative)
                with self.assertRaisesRegex(ValueError, "missing from the selected commit"):
                    require_committed_source_inputs(self.root, self.tree)
                source.unlink()

    def test_modified_assume_unchanged_input_is_rejected_by_blob_comparison(self):
        relative = "android/app/src/main/java/Example.kt"
        self.git("update-index", "--assume-unchanged", relative)
        write(self.root, relative, b"class DifferentImplementation\n")
        with self.assertRaisesRegex(ValueError, "inputs differ from the selected commit"):
            require_committed_source_inputs(self.root, self.tree)

    def test_missing_tracked_input_is_rejected_by_inventory_comparison(self):
        relative = "android/app/src/main/java/Example.kt"
        self.git("update-index", "--assume-unchanged", relative)
        (self.root / relative).unlink()
        with self.assertRaisesRegex(ValueError, "inventory differs"):
            require_committed_source_inputs(self.root, self.tree)


class AtomicOutputTests(unittest.TestCase):
    def test_failure_removes_partial_output_and_never_publishes_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            destination = root / "release"
            with self.assertRaisesRegex(ValueError, "synthetic failure"):
                with atomic_output_directory(destination) as staging:
                    (staging / "partial.apk").write_bytes(b"synthetic")
                    self.assertFalse(destination.exists())
                    raise ValueError("synthetic failure")
            self.assertFalse(destination.exists())
            self.assertEqual(list(root.iterdir()), [])

    def test_success_publishes_only_after_the_context_completes(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "release"
            with atomic_output_directory(destination) as staging:
                (staging / "complete.apk").write_bytes(b"synthetic")
                self.assertFalse(destination.exists())
            self.assertEqual((destination / "complete.apk").read_bytes(), b"synthetic")

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "release"
            destination.mkdir()
            with self.assertRaisesRegex(ValueError, "existing artifacts"):
                with atomic_output_directory(destination):
                    self.fail("Existing output must be rejected before entering staging")
            self.assertTrue(destination.is_dir())

    def test_failed_final_signing_verification_leaves_no_signed_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            key = write(root, "synthetic.key", b"not a key")
            password = write(root, "synthetic.password", b"not a secret")
            write(root, "release/signing-lineage.base64", b"AA==")
            output = root / "signed"
            signed = []
            def pretend_tool(arguments):
                if "sign" in arguments:
                    target = Path(arguments[arguments.index("--out") + 1])
                    target.write_bytes(b"synthetic apk")
                    signed.append(target.name)
                return ""
            arguments = ["sign-release.py", "--input-dir", str(root / "unsigned"), "--output-dir", str(output),
                         "--keystore", str(key), "--password-file", str(password)]
            with patch.object(signing, "ROOT", root), patch("sys.argv", arguments), \
                    patch.object(signing.checks, "run_tool", side_effect=pretend_tool), \
                    patch.object(signing.checks, "sdk_tool", return_value="synthetic-apksigner"), \
                    patch.object(signing.subprocess, "run") as verify, redirect_stdout(io.StringIO()):
                verify.return_value.returncode = 1
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    signing.main()
            self.assertEqual(len(signed), 5)
            self.assertFalse(output.exists())
            self.assertFalse(list(root.glob(".signed.incomplete-*")))


if __name__ == "__main__":
    unittest.main()
