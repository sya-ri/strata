#!/usr/bin/env python3
"""Save and restore immutable release inputs independently of publication attempts."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED


def require(condition, message):
    """Reject missing or conflicting release identities before using their files."""
    if not condition:
        raise ValueError(message)


def sha256(path):
    """Hash a file without keeping large distribution archives in memory."""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    """Read a UTF-8 manifest."""
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    """Write stable, human-readable release metadata."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def inventory(root):
    """Record every regular file; links must never escape a prepared bundle."""
    result = {}
    for path in sorted(root.rglob("*")):
        require(not path.is_symlink(), "Prepared releases cannot contain symbolic links.")
        if path.is_file() and path != root / "prepared.json":
            result[path.relative_to(root).as_posix()] = {"sha256": sha256(path), "size": path.stat().st_size}
    return result


def validate(root, tag, commit):
    """Check the complete saved inventory before publication or remote verification."""
    require(re.fullmatch(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", tag), "Invalid release tag.")
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Invalid source commit.")
    manifest = read_json(root / "prepared.json")
    require(manifest.get("schemaVersion") == 1 and manifest.get("tag") == tag
            and manifest.get("sourceCommit") == commit, "Prepared release source differs.")
    require(manifest.get("files") and manifest["files"] == inventory(root), "Prepared release files differ.")
    return manifest


def prepare(product, repository, output, tag, commit, tag_object):
    """Copy only publication-owned files, preserving the original signatures."""
    require(not output.exists(), "Prepared release output already exists.")
    output.mkdir(parents=True)
    release = product / "build/release"
    version = tag.removeprefix("v")
    for name in ("maven-coordinates.txt", "maven-files.txt"):
        shutil.copyfile(release / name, output / name)
    records = (output / "maven-files.txt").read_text().splitlines()
    require(records and len(records) == len(set(records)), "Publication inventory is empty or duplicated.")
    for record in records:
        group, artifact, suffix = record.split(":")
        require(re.fullmatch(r"[a-zA-Z0-9_.-]+", group) and re.fullmatch(r"[a-zA-Z0-9_.-]+", artifact)
                and re.fullmatch(r"(?:-[A-Za-z0-9_-]+)?\.[A-Za-z0-9]+", suffix), "Unsafe Maven coordinate.")
        relative = Path(group.replace(".", "/")) / artifact / version / f"{artifact}-{version}{suffix}"
        for extension in ("", ".asc"):
            source = repository / (str(relative) + extension)
            target = output / "maven" / (str(relative) + extension)
            require(source.is_file() and not source.is_symlink(), "A signed publication file is missing.")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                with target.open("rb") as stream:
                    checksum = hashlib.file_digest(stream, algorithm).hexdigest()
                target.with_name(target.name + "." + algorithm).write_text(checksum, encoding="ascii")
    shutil.copytree(release / "modrinth", output / "modrinth")
    for artifact in read_json(output / "modrinth/manifest.json")["artifacts"]:
        group, name, artifact_version = artifact["mavenCoordinate"].split(":")
        canonical = output / "maven" / group.replace(".", "/") / name / artifact_version / artifact["fileName"]
        distribution = output / "modrinth" / artifact["relativePath"]
        require(artifact_version == version and sha256(canonical) == artifact["sha256"] == sha256(distribution),
                "The distribution manifest and signed Maven artifact differ.")
        require(distribution.stat().st_size == artifact["size"], "The distribution manifest size differs.")
    shutil.copyfile(product / f"docs/releases/{tag}.md", output / "release-notes.md")
    with tempfile.TemporaryDirectory() as keyring:
        command = ["gpg", "--batch", "--homedir", keyring]
        subprocess.run(command + ["--import"], input=os.environ["ORG_GRADLE_PROJECT_signingInMemoryKey"].encode(), capture_output=True, check=True)
        public_key = subprocess.check_output(command + ["--armor", "--export"])
        require(public_key, "Release signing public key is missing.")
        (output / "signing-key.asc").write_bytes(public_key)
    hangar_path = release / "hangar/manifest.json"
    if hangar_path.is_file():
        hangar = read_json(hangar_path)
        for platform, artifact in hangar["artifacts"].items():
            relative = f"maven/dev/s7a/strata/strata-runtime-{platform.lower()}/{version}/{artifact['fileName']}"
            require(sha256(output / relative) == artifact["sha256"], "Hangar and Maven plugin JARs differ.")
            artifact["path"] = relative
            artifact["canonicalPath"] = relative
        write_json(output / "hangar/manifest.json", hangar)
    with ZipFile(output / "central-bundle.zip", "w", compression=ZIP_DEFLATED) as archive:
        for path in sorted((output / "maven").rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(output / "maven").as_posix())
    write_json(output / "prepared.json", {
        "schemaVersion": 1, "tag": tag, "sourceCommit": commit, "tagObject": tag_object,
        "controllerCommit": os.environ["GITHUB_SHA"], "runId": int(os.environ["GITHUB_RUN_ID"]),
        "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]), "files": inventory(output),
    })
    validate(output, tag, commit)


def unpack(archive_path, output, tag, commit):
    """Extract only bounded regular relative paths and verify the complete inventory."""
    require(not output.exists(), "Prepared release extraction directory already exists.")
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        require(len(names) == len(set(names)) and sum(member.size for member in members) <= 4 * 1024**3,
                "Prepared release archive is duplicated or oversized.")
        for member in members:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in member.name
                    and ":" not in member.name and (member.isfile() or member.isdir()), "Unsafe prepared release archive entry.")
        archive.extractall(output, filter="data")
    return validate(output, tag, commit)


def github(path):
    """Read authenticated repository metadata without exposing a token."""
    result = subprocess.run(["gh", "api", path], capture_output=True, check=False)
    require(result.returncode == 0, "Could not read prepared release provenance from GitHub.")
    return json.loads(result.stdout)


def restore(run_id, tag, commit, output, tag_object=None):
    """Use a successful prepare job even when a later publication job failed."""
    repo = os.environ["GITHUB_REPOSITORY"]
    run = github(f"repos/{repo}/actions/runs/{run_id}")
    require(run["event"] == "workflow_dispatch" and run["head_branch"] == "master"
            and run["path"] == ".github/workflows/publish-release.yml", "Untrusted prepared release workflow.")
    artifacts = github(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    candidates = [a for a in artifacts if a["name"].startswith(f"prepared-{tag}-") and not a["expired"]]
    require(len(candidates) <= 1, "Prepared release artifacts are ambiguous; select the original preparation run.")
    with tempfile.TemporaryDirectory() as directory:
        download = Path(directory)
        archive = download / "release-prepared.tar.gz"
        if candidates:
            artifact = candidates[0]
            attempt = int(artifact["name"].rsplit("-", 1)[1])
            binding = artifact.get("workflow_run", {})
            require(binding.get("id") == int(run_id) and binding.get("head_sha") == run["head_sha"],
                    "Prepared artifact producer differs.")
            wrapper = download / "artifact.zip"
            with wrapper.open("wb") as stream:
                subprocess.run(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact['id']}/zip"], stdout=stream, check=True)
            require(artifact.get("digest") == "sha256:" + sha256(wrapper)
                    and wrapper.stat().st_size == artifact["size_in_bytes"], "Prepared Actions artifact digest or size differs.")
            with ZipFile(wrapper) as files:
                require(sorted(files.namelist()) == ["release-prepared.sha256", "release-prepared.tar.gz"],
                        "Unexpected prepared Actions artifact contents.")
                for name in files.namelist():
                    require(files.getinfo(name).file_size <= 4 * 1024**3, "Prepared Actions artifact is oversized.")
                    with files.open(name) as source, (download / name).open("wb") as target:
                        shutil.copyfileobj(source, target)
            digest = (download / "release-prepared.sha256").read_text().strip()
        else:
            release = github(f"repos/{repo}/releases/tags/{tag}")
            require(release.get("immutable") is True and release.get("tag_name") == tag and not release.get("draft"),
                    "Prepared inputs expired and no immutable release archive is available; rebuild is not automatic.")
            assets = [a for a in release["assets"] if a["name"] == archive.name]
            require(len(assets) == 1 and re.fullmatch(r"sha256:[0-9a-f]{64}", assets[0].get("digest", "")),
                    "The immutable release has no reusable prepared archive with a digest.")
            subprocess.run(["gh", "release", "download", tag, "--repo", repo, "--pattern", archive.name, "--dir", str(download)], check=True)
            digest = assets[0]["digest"].removeprefix("sha256:")
            attempt = None
        require(sha256(archive) == digest, "Prepared archive checksum differs.")
        manifest = unpack(archive, output, tag, commit)
        shutil.copyfile(archive, output.parent / "release-prepared.tar.gz")
    if attempt is None:
        attempt = manifest["runAttempt"]
    require(manifest["runId"] == int(run_id) and manifest["runAttempt"] == attempt
            and manifest["controllerCommit"] == run["head_sha"], "Prepared release producer differs.")
    require(tag_object is None or manifest["tagObject"] == tag_object, "Prepared release tag object differs.")
    jobs = github(f"repos/{repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100")["jobs"]
    require(any(j["name"] == "Prepare release" and j["conclusion"] == "success" for j in jobs), "Release preparation did not succeed.")
    return manifest


def main():
    """Prepare once or restore the same explicit release for any destination."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["prepare", "restore", "verify"])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--tag-object")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--product", type=Path, default=Path("."))
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--output", type=Path, default=Path("build/prepared"))
    args = parser.parse_args()
    if args.operation == "prepare":
        prepare(args.product, args.repository, args.output, args.tag, args.commit, args.tag_object)
        archive_path = args.output.parent / "release-prepared.tar.gz"
        with tarfile.open(archive_path, "w:gz") as archive:
            for path in sorted(args.output.iterdir()):
                archive.add(path, arcname=path.name)
        archive_path.with_name("release-prepared.sha256").write_text(sha256(archive_path) + "\n", encoding="ascii")
    elif args.operation == "restore":
        require(args.run_id is not None, "A successful preparation run ID is required.")
        require(args.tag_object is not None, "The verified tag object is required.")
        restore(args.run_id, args.tag, args.commit, args.output, args.tag_object)
    else:
        validate(args.output, args.tag, args.commit)


if __name__ == "__main__":
    main()
