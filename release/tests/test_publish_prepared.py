"""Exercise publication and partial retries without rebuilding or contacting a service."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).parents[1] / "publish-prepared.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublishPreparedTest(unittest.TestCase):
    def test_central_upload_uses_the_reconciler_identity_and_never_retries_uncertain_writes(self):
        for uncertain in (False, True):
            with self.subTest(uncertain=uncertain), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "prepared"
                root.mkdir()
                (root / "central-bundle.zip").write_bytes(b"original signed bundle")
                (root / "maven-coordinates.txt").write_text("dev.s7a.strata:strata-api\n")
                (root / "prepared.json").write_text(json.dumps({"tag": "v7.8.9"}))
                with patch.dict(os.environ, {"ORG_GRADLE_PROJECT_mavenCentralUsername": "user", "ORG_GRADLE_PROJECT_mavenCentralPassword": "test-token"}), patch.object(publisher, "verify_signatures"), patch.object(publisher, "reconcile", return_value={"state": "absent"}), patch.object(publisher.urllib.request, "build_opener") as opener:
                    upload = opener.return_value.open
                    upload.return_value.__enter__.return_value.read.return_value = b"00000000-0000-0000-0000-000000000001"
                    if uncertain:
                        upload.side_effect = TimeoutError("Unknown upload outcome")
                        with self.assertRaises(TimeoutError):
                            publisher.central(root, "release", True)
                    else:
                        publisher.central(root, "release", True)
                    upload.assert_called_once()
                    request = upload.call_args.args[0]
                    self.assertEqual({"publishingType": ["AUTOMATIC"], "name": ["dev.s7a.strata-7.8.9"]}, parse_qs(urlparse(request.full_url).query))
                    self.assertIn(b"original signed bundle", request.data)

    def test_central_verification_and_exact_retry_do_not_upload(self):
        for operation in ("release", "verify"):
            with patch.object(publisher, "verify_signatures"), patch.object(publisher, "reconcile", return_value={"state": "exact"}), patch.object(publisher.urllib.request, "build_opener") as opener:
                publisher.central(Path("prepared"), operation, False)
                opener.assert_not_called()

    def test_github_resumes_only_missing_assets_and_preserves_original_preparation_bytes(self):
        for already_exists in (False, True):
            with self.subTest(already_exists=already_exists), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "prepared"
                (root / "modrinth/artifacts").mkdir(parents=True)
                name = "strata-runtime-minecraft-fabric-1.20-7.8.9.jar"
                (root / "modrinth/artifacts" / name).write_bytes(b"canonical jar")
                (root.parent / "release-prepared.tar.gz").write_bytes(b"original archive")
                (root / "modrinth/manifest.json").write_text(json.dumps({"artifacts": [{"githubAssetName": name, "gameVersion": "1.20", "relativePath": "artifacts/" + name}]}))
                draft = {"id": 42, "draft": True, "assets": [{"name": name}] if already_exists else []}
                reads = [draft if already_exists else None] + ([] if already_exists else [draft]) + [{"draft": False}]
                with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), patch.object(publisher, "verify_signatures"), patch.object(publisher.urllib.request, "urlopen") as download, patch.object(publisher.subprocess, "check_output", side_effect=[json.dumps(value).encode() for value in reads]), patch.object(publisher.subprocess, "run") as run:
                    download.return_value.__enter__.return_value.read.return_value = b"signature"
                    publisher.github_release(root, "release", {"tag": "v7.8.9"})
                commands = [call.args[0] for call in run.call_args_list]
                uploads = [Path(command[-1]).name for command in commands if command[:3] == ["gh", "release", "upload"]]
                expected = {name + ".asc", "SHA256SUMS", "release-prepared.tar.gz"} | (set() if already_exists else {name})
                self.assertEqual(expected, set(uploads))
                self.assertEqual(b"original archive", (root.parent / "github-bundle/release-prepared.tar.gz").read_bytes())
                self.assertEqual(0 if already_exists else 1, sum(command[:3] == ["gh", "release", "create"] for command in commands))
                self.assertFalse(any("gradlew" in str(command) for command in commands))


if __name__ == "__main__":
    unittest.main()
