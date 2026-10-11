"""Content fingerprint for the phone release inputs, independent of Git and host paths.

Keep the rules in sync with GenerateReleaseBuildProvenance in app/build.gradle.kts.
The digest binds a locally built APK to source contents; it is not reproducibility
or supply-chain attestation and does not describe external Gradle/environment overrides.
"""

import hashlib
import fnmatch
from pathlib import Path
import subprocess

ASSET = "assets/bitchat-build-provenance.json"
ALGORITHM = "bitchat-apk-source-v1"
INPUT_PATTERNS = (
    "android/app/src/main/**",
    "android/app/src/release/**",
    "android/app/*.gradle",
    "android/app/*.gradle.kts",
    "android/app/*.pro",
    "android/app/*.lockfile",
    "android/app/gradle/**",
    "android/*.gradle",
    "android/*.gradle.kts",
    "android/*.properties",
    "android/*.lockfile",
    "android/gradle/**",
    "android/gradlew",
    "android/gradlew.bat",
    "android/wear/*.gradle",
    "android/wear/*.gradle.kts",
    "android/wear/*.lockfile",
)
EXCLUDED_NAMES = {".gradle", ".git"}
EXCLUDED_PATHS = {"android/local.properties"}
TEXT_SUFFIXES = {
    ".kt", ".java", ".xml", ".pro", ".gradle", ".kts", ".properties",
    ".toml", ".json", ".txt", ".md", ".html", ".js", ".css", ".svg",
    ".yml", ".yaml", ".lockfile", ".csv", ".pem", ".base64", ".sh", ".bat",
}


def source_input_paths(root: Path):
    root = root.resolve()
    files = set()
    for pattern in INPUT_PATTERNS:
        # pathlib's trailing ** yields directories; explicitly visit their files.
        candidates = root.glob(pattern + "/*" if pattern.endswith("/**") else pattern)
        for candidate in candidates:
            relative = candidate.relative_to(root)
            if (candidate.is_file() and relative.as_posix() not in EXCLUDED_PATHS
                    and not (set(relative.parts[:-1]) & EXCLUDED_NAMES)):
                if candidate.is_symlink() or not candidate.resolve().is_relative_to(root):
                    raise ValueError("Release source inputs must be files within the repository")
                files.add(relative.as_posix())
    if not {"android/app/build.gradle.kts", "android/app/src/main/AndroidManifest.xml"} <= files:
        raise ValueError("Required phone source inputs unavailable")
    return sorted(files, key=lambda value: value.encode("utf-8"))


def normalized_source_bytes(relative, data):
    source = Path(relative)
    if source.suffix.lower() in TEXT_SUFFIXES or source.name == "gradlew":
        return data.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")
    return data


def is_source_input_path(relative):
    """Match the fixed patterns against a Git path without consulting the checkout."""
    if relative in EXCLUDED_PATHS or set(relative.split("/")[:-1]) & EXCLUDED_NAMES:
        return False
    for pattern in INPUT_PATTERNS:
        if pattern.endswith("/**"):
            if relative.startswith(pattern[:-2]):
                return True
        else:
            parent, name = relative.rsplit("/", 1) if "/" in relative else ("", relative)
            pattern_parent, pattern_name = pattern.rsplit("/", 1)
            if parent == pattern_parent and fnmatch.fnmatchcase(name, pattern_name):
                return True
    return False


def source_provenance(root: Path):
    root = root.resolve()
    files = source_input_paths(root)
    digest = hashlib.sha256((ALGORITHM + "\n").encode("ascii"))
    for relative in files:
        data = normalized_source_bytes(relative, (root / relative).read_bytes())
        digest.update(relative.encode("utf-8") + b"\0")
        digest.update(hashlib.sha256(data).hexdigest().encode("ascii") + b"\n")
    return {"schema": 1, "algorithm": ALGORITHM, "sourceSha256": digest.hexdigest(), "fileCount": len(files)}


def require_committed_source_inputs(root: Path, commit: str):
    """Require every actual release input to exist, unchanged, in the selected tree.

    git status alone skips ignored files and can hide assume-unchanged edits.
    Compare blobs directly, using the same portable text normalization as the APK
    fingerprint. This gate applies only at packaging; local builds may be dirty.
    """
    root = root.resolve()
    paths = source_input_paths(root)
    tree = subprocess.check_output(
        ["git", "-C", str(root), "ls-tree", "-r", "-z", "--full-tree", commit],
        stderr=subprocess.DEVNULL,
    )
    blobs = {}
    committed_paths = set()
    for entry in tree.split(b"\0"):
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        mode, kind, object_id = metadata.split(b" ")
        relative = path.decode("utf-8", errors="surrogateescape")
        if is_source_input_path(relative):
            committed_paths.add(relative)
        if mode in {b"100644", b"100755"} and kind == b"blob":
            blobs[path] = object_id
    if any(path.encode("utf-8") not in blobs for path in paths):
        raise ValueError("Release source inputs are missing from the selected commit, including ignored or untracked files")
    if set(paths) != committed_paths:
        raise ValueError("Release source input inventory differs from the selected commit")
    with subprocess.Popen(
        ["git", "-C", str(root), "cat-file", "--batch"], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    ) as process:
        for relative in paths:
            object_id = blobs[relative.encode("utf-8")]
            process.stdin.write(object_id + b"\n")
            process.stdin.flush()
            header = process.stdout.readline().rstrip(b"\n").split(b" ")
            if len(header) != 3 or header[0] != object_id or header[1] != b"blob" or not header[2].isdigit():
                raise ValueError("Cannot verify committed release source contents")
            size = int(header[2])
            committed = process.stdout.read(size)
            if len(committed) != size or process.stdout.read(1) != b"\n":
                raise ValueError("Incomplete committed release source contents")
            current = normalized_source_bytes(relative, (root / relative).read_bytes())
            if normalized_source_bytes(relative, committed) != current:
                raise ValueError("Release source inputs differ from the selected commit")
        process.stdin.close()
        if process.wait() != 0:
            raise ValueError("Cannot verify committed release source contents")


def fingerprint_scope():
    return {
        "inputPatterns": list(INPUT_PATTERNS),
        "excludedDirectoryNames": sorted(EXCLUDED_NAMES),
        "excludedPaths": sorted(EXCLUDED_PATHS),
        "textSuffixes": sorted(TEXT_SUFFIXES),
        "additionalTextNames": ["gradlew"],
        "normalization": "UTF-8 text CRLF/CR to LF; other bytes unchanged; paths sorted by UTF-8 bytes",
        "meaning": "Source input content binding, not independent reproducibility or external toolchain attestation",
    }
