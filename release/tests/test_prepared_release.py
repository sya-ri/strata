"""Exercise immutable preparation reuse and archive boundaries without network writes."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("prepared", Path(__file__).parents[1] / "prepared-release.py")
prepared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepared)


class PreparedReleaseTest(unittest.TestCase):
    def test_reuse_requires_every_original_byte_and_source_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "runtime.jar").write_bytes(b"published bytes")
            prepared.write_json(root / "prepared.json", {"schemaVersion": 1, "tag": "v7.8.9", "sourceCommit": "a" * 40,
                                                         "files": prepared.inventory(root)})
            prepared.validate(root, "v7.8.9", "a" * 40)
            with self.assertRaises(ValueError):
                prepared.validate(root, "v7.8.9", "b" * 40)
            (root / "runtime.jar").write_bytes(b"different bytes")
            with self.assertRaises(ValueError):
                prepared.validate(root, "v7.8.9", "a" * 40)

    def test_archive_rejects_traversal_and_links_before_extraction(self):
        for name, kind in (("../escape", tarfile.REGTYPE), ("link", tarfile.SYMTYPE)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                archive = root / "release.tar.gz"
                with tarfile.open(archive, "w:gz") as stream:
                    entry = tarfile.TarInfo(name)
                    entry.type = kind
                    entry.linkname = "../escape"
                    stream.addfile(entry, io.BytesIO())
                with self.assertRaises(ValueError):
                    prepared.unpack(archive, root / "restored", "v7.8.9", "a" * 40)
                self.assertFalse((root / "restored").exists())

    def test_failed_preparation_and_expired_inputs_never_rebuild(self):
        run = {"event": "workflow_dispatch", "head_branch": "master", "path": ".github/workflows/publish-release.yml"}
        for artifacts in ([], [{"name": "prepared-v7.8.9-1", "expired": True}]):
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), patch.object(prepared, "github", side_effect=[run, {"artifacts": artifacts}, {"immutable": False}]):
                with self.assertRaises(ValueError):
                    prepared.restore(123, "v7.8.9", "a" * 40, Path("unused"))

    def test_failed_destination_reuses_successful_preparation_and_original_archive(self):
        self.restore_fixture(expired=False)

    def test_expired_actions_artifact_uses_immutable_release_from_the_same_producer(self):
        self.restore_fixture(expired=True)

    def test_unsuccessful_preparation_and_changed_tag_are_rejected(self):
        for conclusion, tag_object in (("failure", "c" * 40), ("success", "d" * 40)):
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                self.restore_fixture(expired=False, conclusion=conclusion, tag_object=tag_object)

    def restore_fixture(self, expired, conclusion="success", tag_object="c" * 40):
        """Simulate a failed publication run whose immutable preparation was retained."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "runtime.jar").write_bytes(b"signed once")
            prepared.write_json(source / "prepared.json", {
                "schemaVersion": 1, "tag": "v7.8.9", "sourceCommit": "a" * 40, "tagObject": "c" * 40,
                "runId": 123, "runAttempt": 1, "controllerCommit": "b" * 40, "files": prepared.inventory(source),
            })
            archive = root / "original.tar.gz"
            with tarfile.open(archive, "w:gz") as stream:
                for file in source.iterdir():
                    stream.add(file, arcname=file.name)
            wrapper = root / "artifact.zip"
            with ZipFile(wrapper, "w") as files:
                files.write(archive, "release-prepared.tar.gz")
                files.writestr("release-prepared.sha256", prepared.sha256(archive))
            run = {"event": "workflow_dispatch", "head_branch": "master", "head_sha": "b" * 40,
                   "path": ".github/workflows/publish-release.yml", "conclusion": "failure"}
            artifact = {"name": "prepared-v7.8.9-1", "expired": expired, "id": 456,
                        "workflow_run": {"id": 123, "head_sha": "b" * 40},
                        "size_in_bytes": wrapper.stat().st_size, "digest": "sha256:" + prepared.sha256(wrapper)}
            responses = [run, {"artifacts": [artifact]}]
            if expired:
                responses.append({"immutable": True, "tag_name": "v7.8.9", "draft": False,
                                  "assets": [{"name": "release-prepared.tar.gz", "digest": "sha256:" + prepared.sha256(archive)}]})
            responses.append({"jobs": [{"name": "Prepare release", "conclusion": conclusion}]})

            def download(command, **kwargs):
                self.assertEqual("gh", command[0])
                if command[1] == "api":
                    self.assertTrue(command[2].endswith("/artifacts/456/zip"))
                    kwargs["stdout"].write(wrapper.read_bytes())
                    return
                self.assertIn("download", command)
                target = Path(command[command.index("--dir") + 1])
                (target / "release-prepared.tar.gz").write_bytes(archive.read_bytes())
                (target / "release-prepared.sha256").write_text(prepared.sha256(archive))

            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), patch.object(prepared, "github", side_effect=responses), patch.object(prepared.subprocess, "run", side_effect=download):
                restored = prepared.restore(123, "v7.8.9", "a" * 40, root / "restored", tag_object)
            self.assertEqual(123, restored["runId"])
            self.assertEqual(archive.read_bytes(), (root / "release-prepared.tar.gz").read_bytes())


if __name__ == "__main__":
    unittest.main()
