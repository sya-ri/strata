#!/usr/bin/env python3
"""Poll approved distributions and request one protected final verification per completed release run."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, build_opener
from zipfile import ZipFile


def load_tool(name):
    """Reuse the controller's API transport and external-state definitions."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


receipts = load_tool("curseforge-receipts")
hangar = load_tool("hangar-release")
curseforge = load_tool("curseforge-release").CurseForgeRelease
DESTINATIONS = {"maven_central", "github_release", "modrinth", "curseforge", "hangar"}


def validate_request(request, run):
    """Only a successful master release producer may authorize the same product's verification."""
    if (run.get("head_branch") != "master" or run.get("event") != "workflow_dispatch" or run.get("conclusion") != "success"
            or request.get("schemaVersion") != 1 or request.get("operation") != "release"
            or request.get("runId") != run["id"] or request.get("runAttempt") != run["run_attempt"]
            or request.get("controllerCommit") != run["head_sha"]):
        raise ValueError("Publication request producer differs.")
    if not re.fullmatch(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", request.get("tag", "")) or not re.fullmatch(r"[0-9a-f]{40}", request.get("sourceCommit", "")):
        raise ValueError("Publication product identity is invalid.")
    selected = request.get("destinations", {})
    if set(selected) != DESTINATIONS or any(type(value) is not bool for value in selected.values()) or not any(selected.values()):
        raise ValueError("Publication destinations differ.")
    title = f"Publish release {request['tag']} ({request['sourceCommit']}) - CurseForge {str(selected['curseforge']).lower()}"
    if run.get("display_title") != title:
        raise ValueError("Publication request differs from its workflow title.")


def read_bundle(artifact, archive, run):
    """Verify producer, archive digest and bounded JSON contents without extracting files."""
    binding = artifact.get("workflow_run", {})
    if binding.get("id") != run["id"] or binding.get("head_sha") != run["head_sha"]:
        raise ValueError("Publication artifact producer differs.")
    if len(archive) != artifact["size_in_bytes"] or len(archive) > 2 * 1024 * 1024 or artifact.get("digest") != "sha256:" + hashlib.sha256(archive).hexdigest():
        raise ValueError("Publication artifact digest or size differs.")
    with ZipFile(io.BytesIO(archive)) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)) or len(names) > 32 or "publication-request.json" not in names:
            raise ValueError("Publication archive inventory differs.")
        evidence = {}
        for entry in bundle.infolist():
            if entry.is_dir():
                continue
            if entry.file_size > 256 * 1024 or not re.fullmatch(r"(?:modrinth-receipts/|curseforge/|hangar/)?[a-z_-]+\.json", entry.filename):
                raise ValueError("Unexpected publication archive entry.")
            evidence[entry.filename] = json.loads(bundle.read(entry))
    request = evidence["publication-request.json"]
    validate_request(request, run)
    expected_name = f"publication-{request['tag']}-{run['id']}-{run['run_attempt']}"
    if artifact.get("name") != expected_name:
        raise ValueError("Publication artifact name differs.")
    return request, evidence


def service_json(url, headers=None):
    """Perform one bounded read with origin-bound credentials and redacted failures."""
    request = Request(url, headers={"User-Agent": "Strata-publication-monitor", **(headers or {})})
    try:
        with build_opener(hangar.NoRedirect()).open(request, timeout=30) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ValueError("Publication status response is too large.")
            return json.loads(raw)
    except HTTPError as error:
        if error.code == 404:
            return None
        raise ValueError(f"Publication status read failed with HTTP {error.code}.") from None
    except OSError:
        raise ValueError("Publication status read failed.") from None


def key(name):
    """Require only the credentials for selected destinations."""
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(name + " is missing.")
    return value


def distributions_ready(request, evidence):
    """Read moderation readiness only; the protected verifier still checks every byte and all source provenance."""
    selected = request["destinations"]
    if selected["modrinth"]:
        records = [record for name, record in evidence.items() if name.startswith("modrinth-receipts/")]
        ids = {record["projectId"] for record in records}
        if len(ids) != 1:
            raise ValueError("Modrinth receipt identity is missing or inconsistent.")
        project_id = ids.pop()
        if not re.fullmatch(r"[A-Za-z0-9]+", project_id):
            raise ValueError("Invalid Modrinth project ID.")
        remote = service_json("https://api.modrinth.com/v2/project/" + project_id)
        if not remote or remote.get("status") != "approved":
            return False
    if selected["curseforge"]:
        receipt = evidence["curseforge/receipt.json"]
        if receipt.get("sourceCommit") != request["sourceCommit"] or receipt.get("tag") != request["tag"]:
            raise ValueError("CurseForge receipt belongs to a different product.")
        project_id = receipt["projectId"]
        if type(project_id) is not int or project_id <= 0 or not receipt["files"]:
            raise ValueError("CurseForge receipt is incomplete.")
        for record in receipt["files"].values():
            file_id = record.get("fileId")
            if type(file_id) is not int or file_id <= 0:
                return False
            remote = service_json(f"https://api.curseforge.com/v1/mods/{project_id}/files/{file_id}", {"x-api-key": key("CURSEFORGE_API_KEY")})
            if not remote:
                return False
            file = remote["data"]
            status = curseforge.FileStatus(file["fileStatus"])
            if status not in {curseforge.FileStatus.APPROVED, curseforge.FileStatus.RELEASED} or file.get("isAvailable") is not True:
                return False
    if selected["hangar"]:
        receipt = evidence["hangar/receipt.json"]
        if receipt.get("sourceCommit") != request["sourceCommit"] or receipt.get("version") != request["tag"][1:] or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", receipt.get("namespace", "")):
            raise ValueError("Hangar receipt belongs to a different product.")
        remote = service_json(hangar.API + "projects/" + receipt["namespace"] + "/versions/" + receipt["version"])
        if not remote or remote.get("visibility") != "public":
            return False
    return True


def verification_requested(request, producer, runs):
    """Allow one automatic verification attempt; failed verification needs explicit maintainer retry."""
    prefix = f"Publish verify {request['tag']} ({request['sourceCommit']}) - CurseForge "
    return any(run.get("display_title", "").startswith(prefix) and run.get("head_branch") == "master"
               and run.get("created_at", "") >= producer["created_at"] for run in runs)


def dispatch(request):
    """Request only verify on master; this monitor never publishes or uploads files."""
    inputs = {name: str(enabled).lower() for name, enabled in request["destinations"].items()}
    inputs.update(operation="verify", tag=request["tag"], source_commit=request["sourceCommit"], confirmation="verify " + request["tag"])
    result = subprocess.run(["gh", "api", "--method", "POST", "repos/sya-ri/strata/actions/workflows/publish-release.yml/dispatches", "--input", "-"],
                            input=json.dumps({"ref": "master", "inputs": inputs}).encode(), capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Unable to request protected final verification.")


def main():
    """Inspect recent successful release requests and dispatch only when their selected services are ready."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if os.environ.get("GITHUB_REPOSITORY") != "sya-ri/strata":
        raise ValueError("Unexpected release repository.")
    prefix = "repos/sya-ri/strata"
    runs = receipts.pages(prefix + "/actions/workflows/publish-release.yml/runs?event=workflow_dispatch", "workflow_runs")
    cutoff = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat().replace("+00:00", "Z")
    for run in runs:
        if run.get("created_at", "") < cutoff or run.get("conclusion") != "success" or not run.get("display_title", "").startswith("Publish release "):
            continue
        artifacts = receipts.pages(f"{prefix}/actions/runs/{run['id']}/artifacts", "artifacts")
        suffix = f"-{run['id']}-{run['run_attempt']}"
        matches = [artifact for artifact in artifacts if artifact["name"].startswith("publication-v") and artifact["name"].endswith(suffix)]
        if not matches:
            continue
        if len(matches) != 1 or matches[0].get("expired") or matches[0]["size_in_bytes"] > 2 * 1024 * 1024:
            raise ValueError("Publication request artifact is unavailable or ambiguous.")
        artifact = matches[0]
        archive = receipts.github(f"{prefix}/actions/artifacts/{artifact['id']}/zip", binary=True)
        request, evidence = read_bundle(artifact, archive, run)
        if verification_requested(request, run, runs):
            continue
        if not distributions_ready(request, evidence):
            print(request["tag"] + ": waiting for distribution approval")
            continue
        if args.dry_run:
            print(request["tag"] + ": ready for protected verification (dry run)")
        else:
            dispatch(request)
            print(request["tag"] + ": requested protected verification")
            # One dispatch per monitor run avoids duplicate decisions from multiple release attempts.
            break


if __name__ == "__main__":
    main()
