#!/usr/bin/env python3
"""Check immutable Hangar releases before the official Gradle uploader runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import subprocess
import urllib.error
import urllib.parse
import urllib.request

API = "https://hangar.papermc.io/api/v1/"
PLATFORMS = {"PAPER", "VELOCITY"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep authentication bound to the documented API origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def require(condition, message):
    """Reject incomplete or conflicting release evidence."""
    if not condition:
        raise ValueError(message)


def request(url, token=None, method="GET", optional=False, maximum_bytes=2 * 1024 * 1024):
    """Read one bounded response without leaking credentials or retrying writes."""
    headers = {"User-Agent": "Strata-release (https://github.com/sya-ri/strata)"}
    if token:
        require(url.startswith(API), "Authentication target differs.")
        headers["Authorization"] = token
    req = urllib.request.Request(url, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=60) as response:
            content = response.read(maximum_bytes + 1)
            require(len(content) <= maximum_bytes, "Hangar response exceeds the expected size.")
            return content
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return None
        raise ValueError(f"Hangar request failed with HTTP {error.code}.") from None
    except (OSError, urllib.error.URLError):
        raise ValueError("Hangar request failed; no write was retried.") from None


def authenticate():
    """Exchange the environment API key for a short-lived API token in memory."""
    key = os.environ.get("HANGAR_API_TOKEN", "")
    require(key, "HANGAR_API_TOKEN is missing.")
    result = json.loads(request(API + "authenticate?apiKey=" + urllib.parse.quote(key, safe=""), method="POST"))
    token = result.get("token")
    require(isinstance(token, str) and token, "Hangar authentication returned no token.")
    return token


def local_manifest(path, canonical=False):
    """Verify every declared plugin JAR against the current generated inventory."""
    manifest = json.loads(path.read_text(encoding="utf-8"))
    require(manifest.get("schemaVersion") == 1, "Unsupported Hangar manifest schema.")
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", manifest.get("version", "")), "Invalid release version.")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", manifest.get("namespace", "")), "Configure the Hangar project namespace.")
    require(manifest.get("channel") == "Release", "Hangar channel must be Release.")
    require(isinstance(manifest.get("description"), str) and manifest["description"].strip(), "Release notes are missing.")
    require(isinstance(manifest.get("projectBody"), str) and manifest["projectBody"].strip(), "Hangar project description is missing.")
    require(set(manifest["artifacts"]) == PLATFORMS, "Expected both Paper and Velocity artifacts.")
    for platform, artifact in manifest["artifacts"].items():
        name = f"strata-runtime-{platform.lower()}-{manifest['version']}-plugin.jar"
        require(artifact["fileName"] == name, "Noncanonical plugin JAR name.")
        require(type(artifact["size"]) is int and 0 < artifact["size"] <= 128 * 1024 * 1024, "Invalid plugin JAR size.")
        require(re.fullmatch(r"[a-f0-9]{64}", artifact["sha256"]), "Invalid plugin JAR hash.")
        source = Path(artifact["canonicalPath" if canonical else "path"])
        require(source.is_file() and not source.is_symlink(), "Plugin JAR is missing or unsafe.")
        require(source.stat().st_size == artifact["size"], "Plugin JAR size differs.")
        require(hashlib.sha256(source.read_bytes()).hexdigest() == artifact["sha256"], "Plugin JAR hash differs.")
        versions = artifact["platformVersions"]
        require(isinstance(versions, list) and versions and all(isinstance(v, str) and v.strip() for v in versions), "Platform versions are missing.")
        require(len(versions) == len(set(versions)), "Platform versions must be unique.")
    return manifest


def compare_version(remote, manifest, project_id):
    """Require exact platform metadata and file identities without overwriting a version."""
    require(remote.get("projectId") == project_id, "Hangar project identity differs.")
    require(remote.get("name") == manifest["version"], "Hangar version differs.")
    require(remote.get("channel", {}).get("name") == manifest["channel"], "Hangar channel differs.")
    require(remote.get("description") == manifest["description"], "Hangar release notes differ.")
    require(set(remote.get("downloads", {})) == PLATFORMS, "Hangar platform inventory differs.")
    require(set(remote.get("platformDependencies", {})) == PLATFORMS, "Hangar platform dependencies differ.")
    require(set(remote.get("pluginDependencies", {})).issubset(PLATFORMS), "Unexpected dependency platform.")
    require(all(not dependencies for dependencies in remote.get("pluginDependencies", {}).values()), "Unexpected Hangar plugin dependencies.")
    for platform, artifact in manifest["artifacts"].items():
        download = remote["downloads"][platform]
        require(download.get("externalUrl") is None, "Unexpected external plugin download.")
        info = download.get("fileInfo", {})
        require(info.get("name") == artifact["fileName"] and info.get("sizeBytes") == artifact["size"]
                and info.get("sha256Hash") == artifact["sha256"], "Hangar plugin bytes differ.")
        require(set(remote["platformDependencies"][platform]) == set(artifact["platformVersions"]), "Hangar supported versions differ.")


def inspect(manifest, token, required=False, verify_public=False):
    """Read exact project/version identities; final verification uses anonymous public metadata."""
    project_url = API + "projects/" + manifest["namespace"]
    read_token = None if required else token
    project = json.loads(request(project_url, read_token))
    owner, name = manifest["namespace"].split("/")
    require(project.get("namespace", {}).get("owner") == owner and project.get("namespace", {}).get("slug") == name,
            "Hangar project namespace differs.")
    require(type(project.get("id")) is int and 0 < project["id"], "Hangar project identity is missing.")
    raw = request(project_url + "/versions/" + manifest["version"], read_token, optional=True)
    if raw is None:
        require(not required, "Hangar release has not been uploaded.")
        return "absent"
    remote = json.loads(raw)
    compare_version(remote, manifest, project["id"])
    visibility = remote.get("visibility")
    require(visibility in {"public", "new", "needsApproval"}, "Hangar release requires author attention.")
    if required:
        require(visibility == "public", "Hangar release is still awaiting publication.")
    if required or (verify_public and visibility == "public"):
        for platform, artifact in manifest["artifacts"].items():
            url = remote["downloads"][platform]["downloadUrl"]
            parsed = urllib.parse.urlsplit(url)
            require(parsed.scheme == "https" and parsed.netloc == "hangarcdn.papermc.io", "Unexpected Hangar CDN origin.")
            require(hashlib.sha256(request(url, maximum_bytes=artifact["size"])).hexdigest() == artifact["sha256"], "Public Hangar CDN content differs.")
    return "exact" if visibility == "public" else "pending"


def preflight(manifest, token):
    """Check upload permission and exact platform version support before any service publication."""
    query = urllib.parse.urlencode({"permissions": "view_public_info,create_version,edit_page", "project": manifest["namespace"].split("/")[1]})
    permission = json.loads(request(API + "permissions/hasAll?" + query, token))
    require(permission.get("result") is True, "Hangar token lacks view_public_info, create_version, or edit_page permission for this project.")
    for platform, artifact in manifest["artifacts"].items():
        catalog = json.loads(request(API + "platforms/" + platform + "/versions"))
        require(isinstance(catalog, list), "Hangar platform catalog is malformed.")
        versions = {version for entry in catalog for version in [entry["version"], *entry.get("subVersions", [])]}
        require(set(artifact["platformVersions"]) <= versions, "Hangar does not recognize a configured platform version.")
    return inspect(manifest, token)


def save_receipt(path, manifest, source_commit, state):
    """Persist redacted attempt evidence; workflow artifact identity authenticates its producer."""
    require(re.fullmatch(r"[0-9a-f]{40}", source_commit or ""), "A source commit is required for Hangar receipts.")
    payload = {"schemaVersion": 1, "namespace": manifest["namespace"], "version": manifest["version"],
               "sourceCommit": source_commit, "state": state,
               "artifacts": {platform: {key: artifact[key] for key in ("fileName", "sha256", "size", "platformVersions")}
                             for platform, artifact in manifest["artifacts"].items()}}
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def stage(manifest, token, source_commit, receipt):
    """Create at most one immutable version with the official uploader, then observe its current visibility."""
    state = preflight(manifest, token)
    if state == "absent":
        save_receipt(receipt, manifest, source_commit, "attempting")
        result = subprocess.run(
            ["bash", "./gradlew", "--no-parallel", "--max-workers=2", "--no-build-cache",
             "publishStrataPublicationToHangar", "-Pstrata.sourceRevision=v" + manifest["version"],
             "-Pstrata.sourceCommit=" + source_commit], capture_output=True, check=False,
        )
        require(result.returncode == 0, "Official Hangar upload did not complete; no upload was retried. Reconcile the exact version before rerunning.")
    state = inspect(manifest, token, verify_public=True)
    require(state != "absent", "Hangar upload outcome is unknown; no upload was retried.")
    project = json.loads(request(API + "projects/" + manifest["namespace"], token))
    if project.get("mainPageContent") != manifest["projectBody"]:
        result = subprocess.run(
            ["bash", "./gradlew", "--no-parallel", "--max-workers=2", "syncStrataPublicationMainResourcePagePageToHangar",
             "-Pstrata.sourceRevision=v" + manifest["version"], "-Pstrata.sourceCommit=" + source_commit],
            capture_output=True, check=False,
        )
        require(result.returncode == 0, "Hangar version is accepted, but its resource page could not be synchronized.")
        project = json.loads(request(API + "projects/" + manifest["namespace"], token))
        require(project.get("mainPageContent") == manifest["projectBody"], "Hangar resource page differs after synchronization.")
    save_receipt(receipt, manifest, source_commit, state)
    return state


def main():
    """Run one explicit release phase; local validation never authenticates or uploads."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["local", "preflight", "stage", "observe", "verify", "canonical"])
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--source-commit")
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    manifest = local_manifest(args.manifest, canonical=args.operation in {"canonical", "stage", "verify"})
    project = json.loads(args.project.read_text(encoding="utf-8"))
    require(manifest["namespace"] == project.get("namespace"), "Hangar manifest differs from the controller project.")
    if args.operation in {"local", "canonical"}:
        print("exact")
        return
    token = None if args.operation == "verify" else authenticate()
    if args.operation == "preflight":
        state = preflight(manifest, token)
    elif args.operation == "stage":
        require(args.receipt is not None, "Hangar upload requires a receipt path.")
        state = stage(manifest, token, args.source_commit, args.receipt)
    else:
        state = inspect(manifest, token, required=args.operation == "verify", verify_public=True)
    if args.receipt:
        save_receipt(args.receipt, manifest, args.source_commit, state)
    print(state)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError) as failure:
        print(f"Hangar release stopped: {failure}", file=sys.stderr)
        sys.exit(1)
