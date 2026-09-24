"""Exercise publication boundaries with a deterministic in-memory HTTP transport."""

import copy
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from email.parser import BytesParser
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError, URLError


def load_release():
    """Import the standalone controller without running its command line."""
    path = Path(__file__).parents[1] / "curseforge-release.py"
    spec = importlib.util.spec_from_file_location("curseforge_release", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.CurseForgeRelease


Release = load_release()


class CurseForgeReleaseTest(unittest.TestCase):
    """A transport records exactly which requests could leave the controller."""

    class Response(io.BytesIO):
        """Expose the small response surface used by urllib's context manager."""

        def __init__(self, url, body):
            super().__init__(body)
            self.url = url

        def geturl(self):
            return self.url

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "artifacts").mkdir()
        self.manifest = {"releaseVersion": "0.2.0", "changelog": "# Changes\n\nA tested release.\n", "artifacts": []}
        self.content = {}
        for game in ["1.20", "26.2"]:
            name = f"strata-runtime-minecraft-fabric-{game}-0.2.0.jar"
            data = f"canonical-{game}".encode()
            self.content[name] = data
            (self.root / "artifacts" / name).write_bytes(data)
            self.manifest["artifacts"].append({
                "fileName": name, "gameVersion": game, "versionName": f"Strata 0.2.0 for Minecraft {game}",
                "relativePath": f"artifacts/{name}", "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            })
        self.write_manifest()
        (self.root / "project.json").write_text(json.dumps({"projectId": 123, "slug": "strata-ui", "title": "Strata UI"}))
        self.remotes = []
        self.requests = []
        self.uploads = []
        self.upload_failure = None
        self.hidden_ids = set()
        self.catalog = [{"type": 1, "versions": [{"id": i + 1, "name": name}
                                                for i, name in enumerate(["Fabric", "Client", "1.20", "26.2"])]}]
        self.minecraft = [{"versionString": name, "approved": True, "gameVersionId": i + 3, "gameVersionTypeId": 1}
                          for i, name in enumerate(["1.20", "26.2"])]
        self.environment = patch.dict(os.environ, {"CURSEFORGE_API_KEY": "read-secret", "CURSEFORGE_TOKEN": "upload-secret"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def write_manifest(self):
        """Persist the exact fixture manifest bytes used to bind receipts."""
        (self.root / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def release(self):
        """Recreate a fresh process view, including any receipt from a prior attempt."""
        return Release(self.root / "manifest.json", self.root / "project.json", "a" * 40,
                       self.root / "receipt.json", opener=self)

    def remote(self, index, status=Release.FileStatus.RELEASED):
        """Build an independently observable file record for an expected artifact."""
        artifact = self.manifest["artifacts"][index]
        return {"id": 100 + index, "modId": 123, "gameId": 432, "fileName": artifact["fileName"],
                "displayName": artifact["versionName"], "fileLength": artifact["size"], "releaseType": 1,
                "gameVersions": [artifact["gameVersion"], "Fabric", "Client"],
                "dependencies": [{"modId": 308769, "relationType": 3}], "fileStatus": status.value,
                "isAvailable": status is Release.FileStatus.RELEASED,
                "downloadUrl": "https://mediafilez.forgecdn.net/files/1/2/" + artifact["fileName"]}

    def open(self, request, timeout):
        """Serve only known API requests and check credential origin separation."""
        self.assertEqual(60, timeout)
        url = request.full_url
        headers = {key.lower(): value for key, value in request.header_items()}
        self.requests.append((request.get_method(), url, headers))
        if url.startswith(Release.UPLOAD_API):
            self.assertEqual("upload-secret", headers.get("x-api-token"))
            self.assertNotIn("x-api-key", headers)
            if url == Release.UPLOAD_API + "/game/versions":
                self.assertEqual("GET", request.get_method())
                body = [item for group in self.catalog for item in group["versions"]]
                return self.Response(url, json.dumps(body).encode())
            self.assertEqual("POST", request.get_method())
            receipt = json.loads((self.root / "receipt.json").read_text())
            self.assertTrue(any(r["state"] == "attempting" for r in receipt["files"].values()))
            if self.upload_failure:
                raise self.upload_failure
            message = BytesParser().parsebytes(b"Content-Type: " + headers["content-type"].encode() + b"\r\nMIME-Version: 1.0\r\n\r\n" + request.data)
            parts = message.get_payload()
            metadata = json.loads(parts[0].get_payload(decode=True))
            name = parts[1].get_filename()
            self.assertEqual(self.content[name], parts[1].get_payload(decode=True))
            self.uploads.append((name, metadata))
            body = {"id": 100 + next(i for i, a in enumerate(self.manifest["artifacts"]) if a["fileName"] == name)}
        elif url.startswith(("https://mediafilez.forgecdn.net/", "https://edge.forgecdn.net/")):
            if url.startswith("https://edge.forgecdn.net/"):
                self.assertEqual("read-secret", headers.get("x-api-key"))
            else:
                self.assertNotIn("x-api-key", headers)
            self.assertNotIn("x-api-token", headers)
            return self.Response(url, self.content[url.rsplit("/", 1)[1]])
        else:
            self.assertEqual("read-secret", headers.get("x-api-key"))
            self.assertNotIn("x-api-token", headers)
            path = url.removeprefix(Release.API)
            if path == "/v1/mods/123":
                body = {"data": {"id": 123, "slug": "strata-ui", "name": "Strata UI", "gameId": 432, "classId": 6}}
            elif path == "/v1/mods/308769":
                body = {"data": {"id": 308769, "slug": "fabric-language-kotlin", "gameId": 432}}
            elif path == "/v2/games/432/versions":
                body = {"data": self.catalog}
            elif path == "/v1/minecraft/version":
                body = {"data": self.minecraft}
            elif path.startswith("/v1/mods/123/files?"):
                index = int(path.split("index=")[1].split("&")[0])
                data = [r for r in self.remotes if r["id"] not in self.hidden_ids]
                body = {"data": data[index:index + 50], "pagination": {"index": index, "totalCount": len(data), "resultCount": len(data[index:index + 50])}}
            elif path.startswith("/v1/mods/123/files/"):
                identifier = int(path.rsplit("/", 1)[1])
                matches = [r for r in self.remotes if r["id"] == identifier and identifier not in self.hidden_ids]
                if not matches:
                    raise HTTPError(url, 404, "not found", {}, None)
                body = {"data": matches[0]}
            else:
                self.fail(f"Unexpected request: {path}")
        return self.Response(url, json.dumps(body).encode())

    def run_phase(self, operation, **kwargs):
        """Suppress summaries while retaining the returned evidence for assertions."""
        with redirect_stdout(io.StringIO()):
            return self.release().run(operation, **kwargs)

    def test_preflight_then_stage_uploads_exact_metadata_and_bytes(self):
        summary = self.run_phase(Release.Operation.PREFLIGHT)
        self.assertEqual(2, len(summary["absent"]))
        self.assertEqual([], self.uploads)
        self.run_phase(Release.Operation.STAGE)
        self.assertEqual(2, len(self.uploads))
        for index, (_, metadata) in enumerate(self.uploads):
            self.assertEqual(self.manifest["changelog"], metadata["changelog"])
            self.assertEqual("release", metadata["releaseType"])
            self.assertEqual([index + 3, 1, 2], metadata["gameVersions"])
            self.assertEqual({"projects": [{"slug": "fabric-language-kotlin", "projectID": 308769, "type": "requiredDependency"}]}, metadata["relations"])

    def test_hidden_accepted_files_are_not_uploaded_again(self):
        self.run_phase(Release.Operation.STAGE)
        summary = self.run_phase(Release.Operation.STAGE)
        self.assertEqual(2, len(summary["pending"]))
        self.assertEqual(2, len(self.uploads))
        with self.assertRaisesRegex(ValueError, "publication is incomplete"):
            self.run_phase(Release.Operation.VERIFY)
        self.remotes = [self.remote(0), self.remote(1)]
        self.assertEqual(2, len(self.run_phase(Release.Operation.VERIFY)["verified"]))
        self.run_phase(Release.Operation.STAGE)
        self.assertEqual(2, len(self.uploads))

    def test_existing_exact_release_needs_no_upload_token(self):
        self.remotes = [self.remote(0), self.remote(1)]
        with patch.dict(os.environ, {"CURSEFORGE_TOKEN": ""}):
            self.run_phase(Release.Operation.STAGE)
            self.run_phase(Release.Operation.VERIFY)
        self.assertEqual([], self.uploads)

    def test_conflicting_later_target_prevents_every_upload(self):
        remote = self.remote(1)
        remote["dependencies"] = []
        self.remotes = [remote]
        with self.assertRaisesRegex(ValueError, "dependency differs"):
            self.run_phase(Release.Operation.STAGE)
        self.assertEqual([], self.uploads)

    def test_unknown_upload_outcome_is_not_retried(self):
        self.upload_failure = URLError("secret-bearing remote diagnostic")
        with self.assertRaisesRegex(ValueError, "unknown outcome"):
            self.run_phase(Release.Operation.STAGE)
        self.assertEqual(1, sum(method == "POST" for method, _, _ in self.requests))
        with self.assertRaisesRegex(ValueError, "Unresolved prior upload"):
            self.run_phase(Release.Operation.STAGE)
        self.assertEqual(1, sum(method == "POST" for method, _, _ in self.requests))

    def test_server_error_on_upload_is_not_retried(self):
        self.upload_failure = HTTPError("unused", 503, "secret response", {}, None)
        with self.assertRaisesRegex(ValueError, "HTTP 503"):
            self.run_phase(Release.Operation.STAGE)
        self.assertEqual(1, sum(method == "POST" for method, _, _ in self.requests))

    def test_missing_history_allows_only_complete_existing_release(self):
        with self.assertRaisesRegex(ValueError, "Previous upload evidence is missing"):
            self.run_phase(Release.Operation.STAGE, may_upload=False)
        self.remotes = [self.remote(0), self.remote(1)]
        self.run_phase(Release.Operation.STAGE, may_upload=False)
        self.assertEqual([], self.uploads)

    def test_remote_bytes_are_verified(self):
        self.remotes = [self.remote(0), self.remote(1)]
        name = self.remotes[1]["fileName"]
        self.content[name] = b"x" * len(self.content[name])
        with self.assertRaisesRegex(ValueError, "Public file bytes differ"):
            self.run_phase(Release.Operation.VERIFY)

    def test_differing_tags_duplicate_files_and_rejections_stop(self):
        for change in ["tags", "duplicate", "rejected", "unknown-status"]:
            with self.subTest(change=change):
                self.remotes = [self.remote(0)]
                if change == "tags":
                    self.remotes[0]["gameVersions"].append("Server")
                elif change == "duplicate":
                    other = copy.deepcopy(self.remotes[0])
                    other["id"] = 200
                    self.remotes.append(other)
                elif change == "rejected":
                    self.remotes[0]["fileStatus"] = Release.FileStatus.REJECTED.value
                else:
                    self.remotes[0]["fileStatus"] = 999
                with self.assertRaises(ValueError):
                    self.run_phase(Release.Operation.STAGE)
                self.assertEqual([], self.uploads)

    def test_receipt_is_bound_to_manifest_and_source(self):
        self.run_phase(Release.Operation.PREFLIGHT)
        self.manifest["changelog"] = "different"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Receipt belongs"):
            self.release()

    def test_accepted_receipts_require_distinct_file_ids(self):
        self.run_phase(Release.Operation.STAGE)
        receipt_path = self.root / "receipt.json"
        original = json.loads(receipt_path.read_text())
        for invalid_id in [None, 100]:
            with self.subTest(file_id=invalid_id):
                receipt = copy.deepcopy(original)
                receipt["files"][self.manifest["artifacts"][1]["fileName"]]["fileId"] = invalid_id
                receipt_path.write_text(json.dumps(receipt))
                with self.assertRaisesRegex(ValueError, "file ID"):
                    self.release()

    def test_local_tampering_and_unsafe_paths_fail_before_requests(self):
        artifact = self.manifest["artifacts"][0]
        (self.root / artifact["relativePath"]).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "Canonical artifact bytes differ"):
            self.release()
        self.assertEqual([], self.requests)

    def test_ambiguous_game_tag_is_never_guessed(self):
        self.minecraft.append(copy.deepcopy(self.minecraft[1]))
        with self.assertRaisesRegex(ValueError, "ambiguous Java Minecraft version"):
            self.run_phase(Release.Operation.STAGE)
        self.assertEqual([], self.uploads)

    def test_same_named_non_java_version_is_not_selected(self):
        self.catalog.append({"type": 99, "versions": [{"id": 50, "name": "26.2"}]})
        self.run_phase(Release.Operation.STAGE)
        self.assertEqual([4, 1, 2], self.uploads[1][1]["gameVersions"])

    def test_all_inventory_pages_are_read(self):
        self.remotes = [{"id": i + 1} for i in range(101)]
        self.assertEqual(101, len(self.release().inventory()))
        self.assertEqual(3, len(self.requests))

    def test_download_origin_and_redirects_are_rejected(self):
        release = self.release()
        with self.assertRaisesRegex(ValueError, "download origin"):
            release.request("https://attacker.invalid/file.jar")
        self.assertEqual([], self.requests)
        self.assertIsNone(Release.NoRedirect().redirect_request(None, None, 302, "", {}, "https://attacker.invalid"))

    def test_receipt_contains_no_credentials(self):
        self.run_phase(Release.Operation.STAGE)
        text = (self.root / "receipt.json").read_text()
        self.assertNotIn("read-secret", text)
        self.assertNotIn("upload-secret", text)

    def test_token_only_upload_resumes_from_accepted_receipts_without_rest_reads(self):
        with patch.dict(os.environ, {"CURSEFORGE_API_KEY": ""}):
            self.assertFalse(self.run_phase(Release.Operation.PREFLIGHT)["remoteChecks"])
            release = self.release()
            release.upload(release.artifacts[0], release.catalog())
            summary = self.run_phase(Release.Operation.STAGE)
            self.assertEqual(2, len(summary["pending"]))
            self.assertEqual([], summary["verified"])
            self.run_phase(Release.Operation.STAGE, may_upload=False)
        self.assertEqual(2, len(self.uploads))
        for index, (_, metadata) in enumerate(self.uploads):
            self.assertEqual([index + 3, 1, 2], metadata["gameVersions"])
        self.assertTrue(all(url.startswith(Release.UPLOAD_API + "/") for _, url, _ in self.requests))
        self.remotes = [self.remote(0), self.remote(1)]
        self.assertEqual(2, len(self.run_phase(Release.Operation.VERIFY)["verified"]))

    def test_token_only_unknown_write_or_missing_history_prevents_new_uploads(self):
        with patch.dict(os.environ, {"CURSEFORGE_API_KEY": ""}):
            with self.assertRaisesRegex(ValueError, "Previous upload evidence is missing"):
                self.run_phase(Release.Operation.STAGE, may_upload=False)
            self.assertEqual([], self.uploads)
            self.upload_failure = URLError("upload outcome unavailable")
            with self.assertRaisesRegex(ValueError, "unknown outcome"):
                self.run_phase(Release.Operation.STAGE)
            with self.assertRaisesRegex(ValueError, "Unresolved prior upload"):
                self.run_phase(Release.Operation.STAGE)
        self.assertEqual(1, sum(method == "POST" for method, _, _ in self.requests))

    def test_token_only_ambiguous_catalog_stops_before_upload(self):
        self.catalog.append({"type": 99, "versions": [{"id": 50, "name": "26.2"}]})
        with patch.dict(os.environ, {"CURSEFORGE_API_KEY": ""}):
            with self.assertRaisesRegex(ValueError, "ambiguous CurseForge version tag"):
                self.run_phase(Release.Operation.STAGE)
        self.assertEqual([], self.uploads)

    def test_optional_verification_makes_no_requests_and_preserves_receipt(self):
        self.run_phase(Release.Operation.STAGE)
        self.requests.clear()
        path = self.root / "receipt.json"
        before = path.read_bytes()
        with patch.dict(os.environ, {"CURSEFORGE_API_KEY": "", "CURSEFORGE_TOKEN": ""}):
            summary = self.run_phase(Release.Operation.VERIFY)
        self.assertEqual("skipped", summary["verification"])
        self.assertEqual([], self.requests)
        self.assertEqual(before, path.read_bytes())

    def test_configured_read_key_errors_never_fall_back_to_token_only_upload(self):
        with patch.object(self, "open", side_effect=HTTPError("unused", 403, "forbidden", {}, None)):
            with self.assertRaisesRegex(ValueError, "HTTP 403"):
                self.run_phase(Release.Operation.STAGE)
        self.assertEqual([], self.uploads)

    def test_read_key_is_sent_only_to_the_authenticated_cdn_origin(self):
        self.remotes = [self.remote(0), self.remote(1)]
        self.remotes[0]["downloadUrl"] = self.remotes[0]["downloadUrl"].replace("mediafilez", "edge")
        self.assertEqual(2, len(self.run_phase(Release.Operation.VERIFY)["verified"]))
        for origin in ("https://edge.forgecdn.net.attacker.invalid/file.jar", "http://edge.forgecdn.net/file.jar",
                       "https://edge.forgecdn.net:443/file.jar", "https://user@edge.forgecdn.net/file.jar"):
            with self.assertRaisesRegex(ValueError, "download origin"):
                self.release().request(origin)


if __name__ == "__main__":
    unittest.main()
