#!/usr/bin/env bash

set -euo pipefail

controller_artifact="${1:-}"
release_evidence_root="${2:-}"
target_release_tag="${3:-}"
expected_target_release_commit="${4:-}"
expected_controller_commit="${5:-}"
evidence_root_tag="${6:-$target_release_tag}"
expected_evidence_root_commit="${7:-$expected_target_release_commit}"
legacy_release_artifact="${8:-}"

[[ -f "$controller_artifact" && ! -L "$controller_artifact" ]] || {
  echo 'The controller Pages artifact.tar must be a regular file.' >&2
  exit 1
}
[[ -d "$release_evidence_root" && ! -L "$release_evidence_root" ]] || {
  echo 'The release Pages evidence root must be a regular directory.' >&2
  exit 1
}
[[ "$target_release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'Pages artifact comparison requires an exact semantic release tag.' >&2
  exit 1
}
[[ "$expected_target_release_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected release Pages commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ "$expected_controller_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected controller Pages commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ "$evidence_root_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'The release Pages evidence root requires an exact semantic release tag.' >&2
  exit 1
}
[[ "$expected_evidence_root_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected release Pages evidence root commit must be a full lowercase Git SHA.' >&2
  exit 1
}
if [[ -n "$legacy_release_artifact" ]]; then
  [[ -f "$legacy_release_artifact" && ! -L "$legacy_release_artifact" ]] || {
    echo 'The legacy release Pages artifact.tar must be a regular file.' >&2
    exit 1
  }
fi

pages_python=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && \
    "$candidate" -c 'import hashlib, pathlib, stat, tarfile' >/dev/null 2>&1; then
    pages_python="$candidate"
    break
  fi
done
[[ -n "$pages_python" ]] || {
  echo 'Python with the standard tarfile module is required to compare Pages artifacts.' >&2
  exit 1
}

"$pages_python" - \
  "$controller_artifact" \
  "$release_evidence_root" \
  "$target_release_tag" \
  "$expected_target_release_commit" \
  "$expected_controller_commit" \
  "$evidence_root_tag" \
  "$expected_evidence_root_commit" \
  "$legacy_release_artifact" <<'PY'
import hashlib
import pathlib
import stat
import sys
import tarfile

controller_archive = pathlib.Path(sys.argv[1])
evidence_root = pathlib.Path(sys.argv[2])
target_release_tag = sys.argv[3]
target_release_version = target_release_tag.removeprefix("v")
target_release_commit = sys.argv[4]
controller_commit = sys.argv[5]
evidence_root_tag = sys.argv[6]
evidence_root_version = evidence_root_tag.removeprefix("v")
evidence_root_commit = sys.argv[7]
legacy_release_archive = pathlib.Path(sys.argv[8]) if sys.argv[8] else None
target_release_receipt = f'{{"commit":"{target_release_commit}","revision":"{target_release_tag}"}}\n'.encode()
evidence_root_receipt = f'{{"commit":"{evidence_root_commit}","revision":"{evidence_root_tag}"}}\n'.encode()
controller_receipt = f'{{"commit":"{controller_commit}","revision":"master"}}\n'.encode()


def file_record(path):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return ("file", size, digest.hexdigest())


def filesystem_tree(root, exclude_releases=False):
    records = {}
    seen = set()

    def visit(directory, prefix):
        for child in sorted(directory.iterdir(), key=lambda item: item.name):
            relative = prefix / child.name
            status = child.lstat()
            if stat.S_ISLNK(status.st_mode):
                raise SystemExit("The release Pages evidence contains a symbolic link.")
            if exclude_releases and not prefix.parts and child.name == "releases":
                if not stat.S_ISDIR(status.st_mode):
                    raise SystemExit("The release Pages evidence releases entry is not a directory.")
                continue
            key = relative.as_posix()
            if key in seen:
                raise SystemExit("The release Pages evidence contains a duplicate path.")
            seen.add(key)
            if stat.S_ISDIR(status.st_mode):
                visit(child, relative)
            elif stat.S_ISREG(status.st_mode):
                records[key] = file_record(child)
            else:
                raise SystemExit("The release Pages evidence contains a non-regular entry.")

    visit(root, pathlib.PurePosixPath())
    return records


def normalized_tar_path(name, label="controller"):
    normalized = name
    while normalized.startswith("./"):
        normalized = normalized[2:]
    normalized = normalized.rstrip("/")
    if normalized in ("", "."):
        return ""
    if "\\" in normalized:
        raise SystemExit(f"The {label} Pages artifact contains a backslash path.")
    path = pathlib.PurePosixPath(normalized)
    if path.is_absolute() or any(part in ("", ".", "..") for part in path.parts):
        raise SystemExit(f"The {label} Pages artifact contains an unsafe path.")
    if path.as_posix() != normalized:
        raise SystemExit(f"The {label} Pages artifact contains a non-canonical path.")
    return normalized


def archive_file_record(archive, member, label):
    source = archive.extractfile(member)
    if source is None:
        raise SystemExit(f"A {label} Pages artifact file has no data.")
    digest = hashlib.sha256()
    size = 0
    with source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return ("file", size, digest.hexdigest())


def legacy_release_trees(archive_path):
    root_records = {}
    target_records = {}
    root_receipts = []
    target_receipts = []
    seen = set()
    releases_root_seen = False
    target_root_seen = False
    target_prefix = f"{target_subtree}/"

    with tarfile.open(archive_path, mode="r:") as archive:
        for member in archive.getmembers():
            normalized = normalized_tar_path(member.name, "legacy release")
            if not normalized:
                if not member.isdir():
                    raise SystemExit("The legacy release Pages artifact root is not a directory.")
                continue
            if normalized in seen:
                raise SystemExit("The legacy release Pages artifact contains a duplicate path.")
            seen.add(normalized)
            if not (member.isdir() or member.isfile()):
                raise SystemExit("The legacy release Pages artifact contains a non-regular entry.")
            if normalized == "source-receipt.json":
                source = archive.extractfile(member)
                root_receipts.append(source.read() if source is not None else b"")
            if normalized == f"{target_subtree}/source-receipt.json":
                source = archive.extractfile(member)
                target_receipts.append(source.read() if source is not None else b"")
            if normalized == "releases":
                if not member.isdir():
                    raise SystemExit("The legacy release Pages releases root is not a directory.")
                releases_root_seen = True
                continue
            if normalized == target_subtree:
                if not member.isdir():
                    raise SystemExit("The legacy immutable release Pages subtree root is not a directory.")
                target_root_seen = True
                continue

            if member.isfile() and normalized != "releases" and not normalized.startswith("releases/"):
                root_records[normalized] = archive_file_record(archive, member, "legacy release")
            if member.isfile() and normalized.startswith(target_prefix):
                relative = normalized[len(target_prefix):]
                if relative in target_records:
                    raise SystemExit("The legacy immutable release Pages subtree contains a duplicate path.")
                target_records[relative] = archive_file_record(archive, member, "legacy release")

    if root_receipts != [target_release_receipt]:
        raise SystemExit("The legacy release Pages root receipt differs from the exact target identity.")
    if target_receipts != [target_release_receipt]:
        raise SystemExit("The legacy immutable release Pages receipt differs from the exact target identity.")
    if not releases_root_seen or not target_root_seen or not root_records or not target_records:
        raise SystemExit("The legacy release Pages root or immutable target subtree is empty or missing.")
    return root_records, target_records


controller_releases = {}
controller_root_receipts = []
controller_target_release_receipts = []
controller_evidence_root_receipts = []
seen = set()
target_subtree = f"releases/{target_release_version}"
evidence_root_subtree = f"releases/{evidence_root_version}"
with tarfile.open(controller_archive, mode="r:") as archive:
    for member in archive.getmembers():
        normalized = normalized_tar_path(member.name)
        if not normalized:
            if not member.isdir():
                raise SystemExit("The controller Pages artifact root is not a directory.")
            continue
        if normalized in seen:
            raise SystemExit("The controller Pages artifact contains a duplicate path.")
        seen.add(normalized)
        if not (member.isdir() or member.isfile()):
            raise SystemExit("The controller Pages artifact contains a non-regular entry.")
        if normalized == "source-receipt.json":
            source = archive.extractfile(member)
            controller_root_receipts.append(source.read() if source is not None else b"")
        if normalized == f"{target_subtree}/source-receipt.json":
            source = archive.extractfile(member)
            controller_target_release_receipts.append(source.read() if source is not None else b"")
        if normalized == f"{evidence_root_subtree}/source-receipt.json":
            source = archive.extractfile(member)
            controller_evidence_root_receipts.append(source.read() if source is not None else b"")
        if normalized == "releases":
            if not member.isdir():
                raise SystemExit("The controller Pages releases root is not a directory.")
            continue
        if not normalized.startswith("releases/"):
            continue
        relative = normalized[len("releases/"):]
        if relative in controller_releases:
            raise SystemExit("The controller Pages release inventory contains a duplicate path.")
        if member.isdir():
            continue
        controller_releases[relative] = archive_file_record(archive, member, "controller")

if controller_root_receipts != [controller_receipt]:
    raise SystemExit("The controller Pages root receipt differs from the exact master commit.")
if controller_target_release_receipts != [target_release_receipt]:
    raise SystemExit("The controller target Pages receipt differs from the exact release tag and commit.")
if controller_evidence_root_receipts != [evidence_root_receipt]:
    raise SystemExit("The controller current Pages receipt differs from the exact evidence root identity.")
target_subtree_prefix = f"{target_release_version}/"
controller_target_subtree = {
    path[len(target_subtree_prefix):]: record
    for path, record in controller_releases.items()
    if path.startswith(target_subtree_prefix)
}
evidence_root_subtree_prefix = f"{evidence_root_version}/"
controller_evidence_root_subtree = {
    path[len(evidence_root_subtree_prefix):]: record
    for path, record in controller_releases.items()
    if path.startswith(evidence_root_subtree_prefix)
}
if not controller_target_subtree or not controller_evidence_root_subtree:
    raise SystemExit("A required controller immutable Pages subtree is empty or missing.")
if legacy_release_archive is not None:
    legacy_root_tree, legacy_target_tree = legacy_release_trees(legacy_release_archive)
    if legacy_root_tree != controller_target_subtree:
        raise SystemExit("The legacy release Pages root differs from the controller target subtree.")
    if legacy_target_tree != controller_target_subtree:
        raise SystemExit("The legacy immutable release Pages subtree differs from the controller target subtree.")

evidence_root_tree = filesystem_tree(evidence_root, exclude_releases=True)
evidence_releases_root = evidence_root / "releases"
if not evidence_releases_root.is_dir() or evidence_releases_root.is_symlink():
    raise SystemExit("The release Pages evidence inventory is missing.")
evidence_releases_tree = filesystem_tree(evidence_releases_root)
evidence_target_root = evidence_releases_root / target_release_version
evidence_current_root = evidence_releases_root / evidence_root_version
for required_root in (evidence_target_root, evidence_current_root):
    if not required_root.is_dir() or required_root.is_symlink():
        raise SystemExit("A required immutable release Pages evidence subtree is missing.")
evidence_target_tree = filesystem_tree(evidence_target_root)
evidence_current_tree = filesystem_tree(evidence_current_root)
if (evidence_root / "source-receipt.json").read_bytes() != evidence_root_receipt:
    raise SystemExit("The release Pages evidence root receipt differs from its exact current tag and commit.")
if (evidence_current_root / "source-receipt.json").read_bytes() != evidence_root_receipt:
    raise SystemExit("The current immutable release Pages evidence receipt differs.")
if (evidence_target_root / "source-receipt.json").read_bytes() != target_release_receipt:
    raise SystemExit("The target immutable release Pages evidence receipt differs.")
if evidence_root_tree != evidence_current_tree:
    raise SystemExit("The independently generated release root differs from its immutable evidence subtree.")
if evidence_root_tree != controller_evidence_root_subtree:
    raise SystemExit("The independently generated release Pages root differs from the controller current subtree.")
if evidence_target_tree != controller_target_subtree:
    raise SystemExit("The independently generated target release subtree differs from the controller target subtree.")
if evidence_releases_tree != controller_releases:
    raise SystemExit("The independently generated immutable Pages inventory differs from the controller artifact.")
PY

printf '%s\t%s\t%s\n' "$target_release_tag" "$expected_target_release_commit" "$expected_controller_commit"
