"""Verify Hangar release identity, canonical bytes, and bounded credential handling."""

import copy
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError


spec = importlib.util.spec_from_file_location("hangar_release", Path(__file__).parents[1] / "hangar-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class HangarReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manifest = {
            "schemaVersion": 1, "version": "0.2.0", "namespace": "owner/Strata", "channel": "Release",
            "description": "# Strata 0.2.0\n", "projectBody": "# Strata", "artifacts": {},
        }
        self.remote = {
            "projectId": 123, "name": "0.2.0", "channel": {"name": "Release"}, "visibility": "public",
            "description": self.manifest["description"], "downloads": {}, "platformDependencies": {}, "pluginDependencies": {},
        }
        self.contents = {}
        for platform, versions in (("PAPER", ["1.20", "26.2"]), ("VELOCITY", ["4.2.0"])):
            name = f"strata-runtime-{platform.lower()}-0.2.0-plugin.jar"
            content = platform.encode()
            source = self.root / name
            source.write_bytes(content)
            canonical = self.root / ("canonical-" + name)
            canonical.write_bytes(content)
            self.contents[platform] = content
            self.manifest["artifacts"][platform] = {
                "fileName": name, "path": str(source), "canonicalPath": str(canonical), "size": len(content),
                "sha256": hashlib.sha256(content).hexdigest(), "platformVersions": versions,
            }
            self.remote["downloads"][platform] = {
                "fileInfo": {"name": name, "sizeBytes": len(content), "sha256Hash": hashlib.sha256(content).hexdigest()},
                "externalUrl": None, "downloadUrl": f"https://hangarcdn.papermc.io/{platform}.jar",
            }
            self.remote["platformDependencies"][platform] = versions
        self.project = {"id": 123, "namespace": {"owner": "owner", "slug": "Strata"}, "mainPageContent": "# Strata"}

    def test_local_and_canonical_jars_must_both_match_the_manifest(self):
        manifest_file = self.root / "manifest.json"
        manifest_file.write_text(json.dumps(self.manifest), encoding="utf-8")
        release.local_manifest(manifest_file)
        release.local_manifest(manifest_file, canonical=True)
        Path(self.manifest["artifacts"]["PAPER"]["canonicalPath"]).write_bytes(b"WRONG")
        with self.assertRaisesRegex(ValueError, "hash differs"):
            release.local_manifest(manifest_file, canonical=True)
        self.manifest["artifacts"]["VELOCITY"]["platformVersions"] = "4.2.0"
        manifest_file.write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "versions are missing"):
            release.local_manifest(manifest_file)

    def test_an_existing_version_requires_exact_metadata_for_both_platforms(self):
        release.compare_version(self.remote, self.manifest, 123)
        for key, value in (("projectId", 456), ("description", "changed"), ("downloads", {}),
                           ("pluginDependencies", {"PAPER": ["OtherPlugin"]}),
                           ("platformDependencies", {"PAPER": ["1.20"], "VELOCITY": ["4.2.0"]})):
            with self.subTest(key=key):
                changed = copy.deepcopy(self.remote)
                changed[key] = value
                with self.assertRaises(ValueError):
                    release.compare_version(changed, self.manifest, 123)
        changed = copy.deepcopy(self.remote)
        changed["downloads"]["PAPER"]["fileInfo"]["sha256Hash"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            release.compare_version(changed, self.manifest, 123)

    def test_absence_requires_an_accessible_matching_project(self):
        with patch.object(release, "request", side_effect=[json.dumps(self.project).encode(), None]):
            self.assertEqual("absent", release.inspect(self.manifest, "token"))
        with patch.object(release, "request", side_effect=[json.dumps(self.project).encode(), None]):
            with self.assertRaisesRegex(ValueError, "not been uploaded"):
                release.inspect(self.manifest, "token", required=True)
        with patch.object(release, "request", side_effect=ValueError("unavailable")):
            with self.assertRaisesRegex(ValueError, "unavailable"):
                release.inspect(self.manifest, "token")

    def test_public_verification_hashes_cdn_bytes_without_forwarding_credentials(self):
        with patch.object(release, "request", side_effect=[json.dumps(self.project).encode(), json.dumps(self.remote).encode(),
                                                         self.contents["PAPER"], self.contents["VELOCITY"]]) as request:
            self.assertEqual("exact", release.inspect(self.manifest, "private-token", required=True))
            self.assertTrue(all(call.args[1] is None for call in request.call_args_list[:2]))
            for call in request.call_args_list[2:]:
                self.assertEqual(1, len(call.args))
                self.assertEqual(len(self.contents[call.args[0].rsplit("/", 1)[1][:-4]]), call.kwargs["maximum_bytes"])
        changed = copy.deepcopy(self.remote)
        changed["downloads"]["PAPER"]["downloadUrl"] = "https://example.org/file.jar"
        with patch.object(release, "request", side_effect=[json.dumps(self.project).encode(), json.dumps(changed).encode()]):
            with self.assertRaisesRegex(ValueError, "CDN origin"):
                release.inspect(self.manifest, "token", required=True)

    def test_final_verification_requires_no_upload_token(self):
        manifest = self.root / "manifest.json"
        project = self.root / "project.json"
        manifest.write_text(json.dumps(self.manifest), encoding="utf-8")
        project.write_text(json.dumps({"namespace": "owner/Strata"}), encoding="utf-8")
        with patch.object(release.sys, "argv", ["hangar-release.py", "verify", "--manifest", str(manifest), "--project", str(project)]):
            with patch.object(release, "authenticate") as authenticate, patch.object(release, "inspect", return_value="exact") as inspect:
                with patch.object(release.sys, "stdout", io.StringIO()):
                    release.main()
                authenticate.assert_not_called()
                self.assertIsNone(inspect.call_args.args[1])
                self.assertTrue(inspect.call_args.kwargs["required"])

    def test_preflight_requires_upload_permission_and_recognized_platform_versions(self):
        responses = [b'{"result":true}', b'[{"version":"1.20","subVersions":[]},{"version":"26.2","subVersions":[]}]',
                     b'[{"version":"4.2.0","subVersions":[]}]', json.dumps(self.project).encode(), None]
        with patch.object(release, "request", side_effect=responses):
            self.assertEqual("absent", release.preflight(self.manifest, "token"))
        with patch.object(release, "request", return_value=b'{"result":false}'):
            with self.assertRaisesRegex(ValueError, "permission"):
                release.preflight(self.manifest, "token")

    def test_stage_skips_exact_versions_and_never_retries_uncertain_uploads(self):
        receipt = self.root / "receipt.json"
        commit = "a" * 40
        request_patch = patch.object(release, "request", return_value=json.dumps(self.project).encode())
        request_patch.start()
        self.addCleanup(request_patch.stop)
        for existing in ("exact", "pending"):
            with patch.object(release, "preflight", return_value=existing), patch.object(release, "inspect", return_value=existing):
                with patch.object(release.subprocess, "run") as upload:
                    self.assertEqual(existing, release.stage(self.manifest, "token", commit, receipt))
                    upload.assert_not_called()
        with patch.object(release, "preflight", return_value="absent"), patch.object(release.subprocess, "run") as upload:
            upload.return_value.returncode = 1
            with self.assertRaisesRegex(ValueError, "no upload was retried"):
                release.stage(self.manifest, "token", commit, receipt)
            self.assertEqual(1, upload.call_count)
            self.assertEqual("attempting", json.loads(receipt.read_text())["state"])
            self.assertNotIn("token", receipt.read_text())
        with patch.object(release, "preflight", return_value="absent"), patch.object(release.subprocess, "run") as upload:
            upload.return_value.returncode = 0
            with patch.object(release, "inspect", return_value="pending"):
                self.assertEqual("pending", release.stage(self.manifest, "token", commit, receipt))
                self.assertEqual(1, upload.call_count)
                self.assertIn("publishStrataPublicationToHangar", upload.call_args.args[0])

    def test_an_accepted_version_can_retry_only_resource_page_synchronization(self):
        receipt = self.root / "receipt.json"
        with patch.object(release, "preflight", return_value="exact"), patch.object(release, "inspect", return_value="exact"):
            with patch.object(release, "request", side_effect=[b'{"mainPageContent":"old"}', json.dumps(self.project).encode()]):
                with patch.object(release.subprocess, "run") as upload:
                    upload.return_value.returncode = 0
                    self.assertEqual("exact", release.stage(self.manifest, "token", "a" * 40, receipt))
                    self.assertEqual(1, upload.call_count)
                    self.assertIn("syncStrataPublicationMainResourcePagePageToHangar", upload.call_args.args[0])
                    self.assertNotIn("publishStrataPublicationToHangar", upload.call_args.args[0])

    def test_hidden_versions_are_pending_but_rejected_versions_stop_publication(self):
        for visibility, expected in (("needsApproval", "pending"), ("new", "pending"), ("softDelete", None), ("needsChanges", None)):
            changed = copy.deepcopy(self.remote)
            changed["visibility"] = visibility
            with patch.object(release, "request", side_effect=[json.dumps(self.project).encode(), json.dumps(changed).encode()]):
                if expected:
                    self.assertEqual(expected, release.inspect(self.manifest, "token"))
                else:
                    with self.assertRaisesRegex(ValueError, "attention"):
                        release.inspect(self.manifest, "token")

    def test_response_bounds_and_authentication_failures_do_not_leak_keys(self):
        with patch.object(release.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value = io.BytesIO(b"1234")
            with self.assertRaisesRegex(ValueError, "expected size"):
                release.request(release.API + "projects/owner/Strata", maximum_bytes=3)
        with patch.dict(os.environ, {"HANGAR_API_TOKEN": "private-secret"}):
            with patch.object(release.urllib.request, "build_opener") as opener:
                opener.return_value.open.side_effect = HTTPError(release.API + "authenticate?apiKey=private-secret", 403, "private-secret", {}, None)
                with self.assertRaisesRegex(ValueError, "HTTP 403") as error:
                    release.authenticate()
                self.assertNotIn("private-secret", str(error.exception))
                self.assertEqual(1, opener.return_value.open.call_count)
        with self.assertRaisesRegex(ValueError, "Authentication target differs"):
            release.request("https://example.org", token="private-token")


if __name__ == "__main__":
    unittest.main()
