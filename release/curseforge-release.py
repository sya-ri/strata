#!/usr/bin/env python3
"""Publish canonical release manifests through the documented CurseForge APIs.

The controller supplies immutable source identity and already verified artifacts.
Receipts are execution evidence, never build-cache inputs or publication authority.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
import uuid
from enum import Enum, IntEnum
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class CurseForgeRelease:
    """Own one sequential reconciliation; credentials are sent only to fixed APIs."""

    class Operation(Enum):
        """Explicit read-only or append-only release phases."""

        PREFLIGHT = "preflight"
        STAGE = "stage"
        VERIFY = "verify"

    class State(Enum):
        """Local evidence distinguishes an uncertain write from an accepted file."""

        ATTEMPTING = "attempting"
        PENDING = "pending"
        VERIFIED = "verified"

    class FileStatus(IntEnum):
        """Wire values from the official FileStatus schema; unknown values fail."""

        PROCESSING = 1
        CHANGES_REQUIRED = 2
        UNDER_REVIEW = 3
        APPROVED = 4
        REJECTED = 5
        MALWARE_DETECTED = 6
        DELETED = 7
        ARCHIVED = 8
        TESTING = 9
        RELEASED = 10
        READY_FOR_REVIEW = 11
        DEPRECATED = 12
        BAKING = 13
        AWAITING_PUBLISHING = 14
        FAILED_PUBLISHING = 15
        COOKING = 16
        COOKED = 17
        UNDER_MANUAL_REVIEW = 18
        SCANNING_FOR_MALWARE = 19
        PROCESSING_FILE = 20
        PENDING_RELEASE = 21
        READY_FOR_COOKING = 22
        POST_PROCESSING = 23

    class ReleaseType(IntEnum):
        """Decode the external file release channel at the API boundary."""

        RELEASE = 1
        BETA = 2
        ALPHA = 3

    class RelationType(IntEnum):
        """Documented dependency relation wire values."""

        EMBEDDED = 1
        OPTIONAL = 2
        REQUIRED = 3
        TOOL = 4
        INCOMPATIBLE = 5
        INCLUDE = 6

    class RequestError(ValueError):
        """Retain only an HTTP status, without request credentials or server text."""

        def __init__(self, status):
            super().__init__(f"CurseForge HTTP {status}; no write was retried.")
            self.status = status

    class NoRedirect(HTTPRedirectHandler):
        """Never forward API credentials or repeat uploads through a redirect."""

        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    API = "https://api.curseforge.com"
    UPLOAD_API = "https://minecraft.curseforge.com/api"

    def __init__(self, manifest_path, project_path, source_commit, receipt_path, *, opener=None):
        """Validate local inputs before reading credentials or making requests."""
        self.manifest_path = Path(manifest_path).absolute()
        self.receipt_path = Path(receipt_path)
        self.manifest = self.read_json(self.manifest_path)
        self.project = self.read_json(Path(project_path))
        self.require(re.fullmatch(r"[0-9a-f]{40}", source_commit), "An exact source commit is required.")
        self.project_id = self.project.get("projectId")
        self.require(type(self.project_id) is int and 0 < self.project_id,
                     "Set the created CurseForge projectId in release/curseforge-project.json.")
        self.require(self.project.get("slug") == "strata-ui" and self.project.get("title") == "Strata UI",
                     "Unexpected CurseForge project identity.")
        version = self.manifest.get("releaseVersion", "")
        self.require(re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version),
                     "A stable release version is required.")
        self.require(isinstance(self.manifest.get("changelog"), str) and self.manifest["changelog"].strip(),
                     "The canonical release changelog is missing.")
        self.artifacts = self.manifest.get("artifacts")
        self.require(isinstance(self.artifacts, list) and self.artifacts, "The artifact inventory is empty.")
        names, versions = set(), set()
        for artifact in self.artifacts:
            game = artifact.get("gameVersion", "")
            name = artifact.get("fileName", "")
            self.require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)+", game), "Invalid Minecraft target.")
            self.require(name == f"strata-runtime-minecraft-fabric-{game}-{version}.jar", "Noncanonical JAR name.")
            self.require(name not in names and game not in versions, "Duplicate release target.")
            names.add(name)
            versions.add(game)
            self.require(artifact.get("relativePath") == f"artifacts/{name}", "Unsafe artifact path.")
            self.require(artifact.get("versionName") == f"Strata {version} for Minecraft {game}", "Display name differs.")
            self.require(type(artifact.get("size")) is int and 0 < artifact["size"], "Invalid artifact size.")
            self.require(re.fullmatch(r"[0-9a-f]{64}", artifact.get("sha256", "")), "Invalid SHA-256.")
            self.verify_local(artifact)
        identity = {
            "projectId": self.project_id,
            "sourceCommit": source_commit,
            "tag": f"v{version}",
            "manifestSha256": hashlib.sha256(self.manifest_path.read_bytes()).hexdigest(),
        }
        self.receipt = {"schemaVersion": 1, **identity, "files": {}}
        if self.receipt_path.exists():
            previous = self.read_json(self.receipt_path)
            self.require(previous.get("schemaVersion") == 1 and all(previous.get(k) == v for k, v in identity.items()),
                         "Receipt belongs to a different project, source, or manifest.")
            self.require(isinstance(previous.get("files"), dict) and set(previous["files"]) <= names,
                         "Receipt contains foreign files.")
            file_ids = set()
            for name, record in previous["files"].items():
                state = self.State(record["state"])
                self.require(record.get("sha256") == next(a["sha256"] for a in self.artifacts if a["fileName"] == name),
                             "Receipt artifact hash differs.")
                if record.get("fileId") is not None:
                    self.require(type(record["fileId"]) is int and 0 < record["fileId"], "Invalid receipt file ID.")
                    self.require(record["fileId"] not in file_ids, "Receipt reuses a file ID.")
                    file_ids.add(record["fileId"])
                else:
                    self.require(state is self.State.ATTEMPTING, "Accepted receipt has no file ID.")
            self.receipt = previous
        self.opener = opener or build_opener(self.NoRedirect())
        self.read_key = os.environ.get("CURSEFORGE_API_KEY", "")
        self.upload_token = os.environ.get("CURSEFORGE_TOKEN", "")

    @staticmethod
    def require(condition, message):
        """Raise a credential-free operational error instead of a bare assertion."""
        if not condition:
            raise ValueError(message)

    @staticmethod
    def read_json(path):
        """Read a regular UTF-8 JSON input, rejecting symbolic links."""
        CurseForgeRelease.require(path.is_file() and not path.is_symlink(), f"Not a regular input file: {path.name}")
        return json.loads(path.read_text(encoding="utf-8"))

    def verify_local(self, artifact):
        """Recheck the upload bytes against the canonical manifest."""
        path = self.manifest_path.parent / artifact["relativePath"]
        self.require(path.is_file() and not path.is_symlink() and not path.parent.is_symlink(), "Unsafe artifact file.")
        data = path.read_bytes()
        self.require(len(data) == artifact["size"] and hashlib.sha256(data).hexdigest() == artifact["sha256"],
                     f"Canonical artifact bytes differ: {artifact['fileName']}")
        return data

    def request(self, url, *, upload=None, limit=32 * 1024 * 1024):
        """Make bounded reads or exactly one upload; never expose response bodies."""
        headers = {"User-Agent": "sya-ri/strata release (https://gh.s7a.dev/strata/)", "Accept": "application/json"}
        if upload is not None:
            self.require(url == f"{self.UPLOAD_API}/projects/{self.project_id}/upload-file", "Unsafe upload endpoint.")
            self.require(self.upload_token, "CURSEFORGE_TOKEN is required; no upload was attempted.")
            headers.update({"X-Api-Token": self.upload_token, "Content-Type": upload[0]})
        elif url.startswith(self.API + "/"):
            self.require(self.read_key, "CURSEFORGE_API_KEY is required for REST API reads.")
            headers["x-api-key"] = self.read_key
        else:
            parsed = urlsplit(url)
            self.require(parsed.scheme == "https" and parsed.hostname in {"mediafilez.forgecdn.net", "edge.forgecdn.net"}
                         and parsed.port is None and parsed.username is None and not parsed.query and not parsed.fragment,
                         "Unexpected CurseForge download origin.")
            if parsed.hostname == "edge.forgecdn.net":
                self.require(self.read_key, "CURSEFORGE_API_KEY is required for authenticated CDN verification.")
                headers["x-api-key"] = self.read_key
        for attempt in range(1 if upload else 3):
            try:
                with self.opener.open(Request(url, data=upload[1] if upload else None, headers=headers), timeout=60) as response:
                    self.require(response.geturl() == url, "Unexpected response redirect.")
                    result = response.read(limit + 1)
                    self.require(len(result) <= limit, "CurseForge response exceeds its expected size bound.")
                    return result
            except HTTPError as error:
                error.close()
                if upload or error.code not in {429, 500, 502, 503, 504} or attempt == 2:
                    raise self.RequestError(error.code) from None
            except (URLError, TimeoutError, OSError):
                if upload or attempt == 2:
                    raise ValueError("CurseForge request failed; an attempted upload has an unknown outcome.") from None
            time.sleep(attempt + 1)
        raise ValueError("CurseForge read retries exhausted.")

    def api(self, path):
        """Decode a response from the fixed read API."""
        return json.loads(self.request(self.API + path))

    def catalog(self):
        """Resolve numeric tags with the read API, or let the Upload API resolve exact names."""
        if not self.read_key:
            self.require(self.upload_token, "CURSEFORGE_TOKEN is required; no upload was attempted.")
            return None
        project = self.api(f"/v1/mods/{self.project_id}")["data"]
        self.require(project.get("id") == self.project_id and project.get("slug") == self.project["slug"]
                     and project.get("name") == self.project["title"] and project.get("gameId") == 432
                     and project.get("classId") == 6, "CurseForge project identity or Minecraft Mods class differs.")
        dependency = self.api("/v1/mods/308769")["data"]
        self.require(dependency.get("id") == 308769 and dependency.get("slug") == "fabric-language-kotlin"
                     and dependency.get("gameId") == 432, "Fabric Language Kotlin dependency identity differs.")
        catalog = self.api("/v2/games/432/versions")["data"]
        minecraft = self.api("/v1/minecraft/version")["data"]
        tags = {}
        for name in {"Fabric", "Client"} | {a["gameVersion"] for a in self.artifacts}:
            matches = [item for group in catalog for item in group["versions"] if item.get("name") == name]
            if name not in {"Fabric", "Client"}:
                native = [item for item in minecraft if item.get("versionString") == name and item.get("approved") is True]
                self.require(len(native) == 1, f"Missing or ambiguous Java Minecraft version: {name}")
                matches = [item for group in catalog if group["type"] == native[0]["gameVersionTypeId"]
                           for item in group["versions"] if item.get("name") == name and item.get("id") == native[0]["gameVersionId"]]
            self.require(len(matches) == 1, f"Missing or ambiguous CurseForge version tag: {name}")
            identifier = matches[0].get("id")
            self.require(type(identifier) is int and 0 < identifier, "Invalid version tag ID.")
            tags[name] = identifier
        self.require(len(set(tags.values())) == len(tags), "Version tags share an ID.")
        return tags

    def inventory(self):
        """Enumerate every public file page; changing or incomplete counts fail closed."""
        files, total = [], None
        while total is None or len(files) < total:
            index = len(files)
            self.require(index < 10000, "CurseForge file inventory exceeds the documented pagination limit.")
            page = self.api(f"/v1/mods/{self.project_id}/files?index={index}&pageSize=50")
            pagination, data = page["pagination"], page["data"]
            count = pagination.get("totalCount")
            self.require(type(count) is int and 0 <= count and count <= 10000, "Invalid file inventory count.")
            if total is None:
                total = count
            self.require(count == total and pagination.get("index") == index
                         and pagination.get("resultCount") == len(data) and len(data) <= 50
                         and len(data) <= total - index and (data or index == total), "Incomplete or changing file inventory.")
            files.extend(data)
        ids = [f.get("id") for f in files]
        self.require(all(type(i) is int and 0 < i for i in ids) and len(ids) == len(set(ids)), "Duplicate or invalid file IDs.")
        return files

    def verify_remote(self, artifact, remote):
        """Check metadata and, when publicly available, every downloaded byte."""
        self.require(remote.get("modId") == self.project_id and remote.get("gameId") == 432
                     and remote.get("fileName") == artifact["fileName"]
                     and remote.get("displayName") == artifact["versionName"]
                     and remote.get("fileLength") == artifact["size"]
                     and self.ReleaseType(remote.get("releaseType")) is self.ReleaseType.RELEASE,
                     f"Existing file metadata differs: {artifact['fileName']}")
        self.require(set(remote.get("gameVersions", [])) == {artifact["gameVersion"], "Fabric", "Client"},
                     f"Existing file version tags differ: {artifact['fileName']}")
        dependencies = remote.get("dependencies", [])
        self.require(len(dependencies) == 1 and dependencies[0].get("modId") == 308769
                     and self.RelationType(dependencies[0].get("relationType")) is self.RelationType.REQUIRED,
                     "Required dependency differs.")
        status = self.FileStatus(remote["fileStatus"])
        self.require(status not in {self.FileStatus.CHANGES_REQUIRED, self.FileStatus.REJECTED,
                                   self.FileStatus.MALWARE_DETECTED, self.FileStatus.DELETED,
                                   self.FileStatus.ARCHIVED, self.FileStatus.DEPRECATED, self.FileStatus.FAILED_PUBLISHING},
                     f"CurseForge requires attention: file {remote['id']} is {status.name}.")
        available = status in {self.FileStatus.APPROVED, self.FileStatus.RELEASED} and remote.get("isAvailable") is True
        if not available:
            return self.State.PENDING
        url = remote.get("downloadUrl")
        if not url:
            url = self.api(f"/v1/mods/{self.project_id}/files/{remote['id']}/download-url")["data"]
        data = self.request(url, limit=artifact["size"])
        self.require(len(data) == artifact["size"] and hashlib.sha256(data).hexdigest() == artifact["sha256"],
                     f"Public file bytes differ: {artifact['fileName']}")
        return self.State.VERIFIED

    def save(self):
        """Atomically persist credential-free evidence before and after each write."""
        self.receipt_path.parent.mkdir(parents=True, exist_ok=True)
        self.require(not self.receipt_path.is_symlink(), "Unsafe receipt destination.")
        temporary = self.receipt_path.with_suffix(".tmp")
        self.require(not temporary.is_symlink(), "Unsafe temporary receipt destination.")
        with temporary.open("w", encoding="utf-8", newline="\n") as output:
            json.dump(self.receipt, output, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        temporary.replace(self.receipt_path)

    def upload(self, artifact, tags):
        """Mark the write uncertain first; one successful response records its ID."""
        self.require(self.upload_token, "CURSEFORGE_TOKEN is required; no upload was attempted.")
        data = self.verify_local(artifact)
        metadata = {
            "changelog": self.manifest["changelog"], "changelogType": "markdown",
            "displayName": artifact["versionName"], "releaseType": "release",
            "isMarkedForManualRelease": False,
            "relations": {"projects": [{"slug": "fabric-language-kotlin", "projectID": 308769, "type": "requiredDependency"}]},
        }
        if tags is None:
            metadata["gameVersionNames"] = [artifact["gameVersion"], "Fabric", "Client"]
        else:
            metadata["gameVersions"] = [tags[artifact["gameVersion"]], tags["Fabric"], tags["Client"]]
        boundary = "strata-" + uuid.uuid4().hex
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()
            + json.dumps(metadata).encode() +
            f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{artifact["fileName"]}"\r\nContent-Type: application/java-archive\r\n\r\n'.encode()
            + data + f"\r\n--{boundary}--\r\n".encode()
        )
        record = {"sha256": artifact["sha256"], "state": self.State.ATTEMPTING.value, "fileId": None}
        self.receipt["files"][artifact["fileName"]] = record
        self.save()
        result = json.loads(self.request(f"{self.UPLOAD_API}/projects/{self.project_id}/upload-file",
                                        upload=(f"multipart/form-data; boundary={boundary}", body)))
        identifier = result.get("id")
        self.require(type(identifier) is int and 0 < identifier, "Upload outcome unknown: no valid file ID returned.")
        record.update(state=self.State.PENDING.value, fileId=identifier)
        self.save()

    def run(self, operation, *, may_upload=True):
        """Preflight every target before any append; verification never uploads."""
        if operation is self.Operation.VERIFY and not self.read_key:
            summary = {"operation": operation.value, "projectId": self.project_id,
                       "verification": "skipped", "reason": "CURSEFORGE_API_KEY is not configured"}
            print(json.dumps(summary))
            return summary
        tags = self.catalog()
        files = self.inventory() if self.read_key else []
        absent, pending, verified = [], [], []
        for artifact in self.artifacts:
            name = artifact["fileName"]
            matches = [f for f in files if f.get("fileName") == name or f.get("displayName") == artifact["versionName"]]
            self.require(len(matches) <= 1, f"Duplicate CurseForge release file: {name}")
            record = self.receipt["files"].get(name)
            if not self.read_key and record and record.get("fileId"):
                pending.append(name)
                continue
            if not matches and record and record.get("fileId"):
                try:
                    matches = [self.api(f"/v1/mods/{self.project_id}/files/{record['fileId']}")["data"]]
                except self.RequestError as error:
                    # Public visibility is not proof of absence. Keep the accepted ID.
                    if error.status == 404:
                        pending.append(name)
                        continue
                    raise
            if matches:
                remote = matches[0]
                self.require(not record or not record.get("fileId") or record["fileId"] == remote["id"], "Receipt file ID differs.")
                state = self.verify_remote(artifact, remote)
                self.receipt["files"][name] = {"sha256": artifact["sha256"], "state": state.value, "fileId": remote["id"]}
                (verified if state is self.State.VERIFIED else pending).append(name)
            else:
                self.require(record is None, f"Unresolved prior upload for {name}; reconcile the Authors Console before retrying.")
                absent.append(artifact)
        if operation is not self.Operation.VERIFY:
            self.require(may_upload or not absent,
                         "Previous upload evidence is missing; reconcile unresolved files in the Authors Console before retrying.")
        self.save()
        if operation is self.Operation.STAGE:
            for artifact in absent:
                self.upload(artifact, tags)
                pending.append(artifact["fileName"])
            absent = []
        summary = {"operation": operation.value, "projectId": self.project_id, "remoteChecks": bool(self.read_key),
                   "absent": [a["fileName"] for a in absent], "pending": pending, "verified": verified}
        print(json.dumps(summary))
        if operation is self.Operation.VERIFY:
            self.require(not absent and not pending, "CurseForge publication is incomplete; review is pending or files are absent.")
        return summary


def main():
    """Run a single explicit phase; ordinary builds never invoke this entrypoint."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=[operation.value for operation in CurseForgeRelease.Operation])
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--receipt", required=True)
    parser.add_argument("--history", help="Controller-restored history; missing prior write evidence forbids new uploads.")
    arguments = parser.parse_args()
    try:
        release = CurseForgeRelease(arguments.manifest, arguments.project, arguments.source_commit, arguments.receipt)
        may_upload = True
        if arguments.history:
            history = release.read_json(Path(arguments.history))
            release.require(type(history.get("mayUpload")) is bool, "Invalid upload history.")
            may_upload = history["mayUpload"]
        release.run(CurseForgeRelease.Operation(arguments.operation), may_upload=may_upload)
    except (ValueError, KeyError, TypeError, OSError) as error:
        # Network response bodies and request headers are deliberately never printed.
        print(f"CurseForge release stopped: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
