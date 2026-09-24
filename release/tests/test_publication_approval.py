"""Exercise approval monitoring without accessing services or dispatching real workflows."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("approval", Path(__file__).parents[1] / "await-publication.py")
approval = importlib.util.module_from_spec(spec)
spec.loader.exec_module(approval)


class PublicationApprovalTest(unittest.TestCase):
    def setUp(self):
        self.request = {"schemaVersion": 1, "operation": "release", "tag": "v0.2.0", "sourceCommit": "a" * 40,
                        "controllerCommit": "b" * 40, "runId": 123, "runAttempt": 2,
                        "destinations": {name: True for name in approval.DESTINATIONS}}
        self.run = {"id": 123, "run_attempt": 2, "head_sha": "b" * 40, "head_branch": "master", "conclusion": "success",
                    "event": "workflow_dispatch", "created_at": "2026-09-24T00:00:00Z",
                    "display_title": "Publish release v0.2.0 (" + "a" * 40 + ") - CurseForge true"}

    def test_producer_and_selected_product_must_match(self):
        approval.validate_request(self.request, self.run)
        for field, value in (("operation", "verify"), ("tag", "../escape"), ("sourceCommit", "bad"),
                             ("controllerCommit", "c" * 40), ("runAttempt", 1), ("destinations", {"hangar": "true"})):
            changed = {**self.request, field: value}
            with self.assertRaises(ValueError):
                approval.validate_request(changed, self.run)
        with self.assertRaises(ValueError):
            approval.validate_request(self.request, {**self.run, "head_branch": "pull-request"})

    def test_archive_digest_and_product_identity_are_checked_before_use(self):
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as archive:
            archive.writestr("publication-request.json", json.dumps(self.request))
        content = buffer.getvalue()
        artifact = {"name": "publication-v0.2.0-123-2", "size_in_bytes": len(content),
                    "digest": "sha256:" + hashlib.sha256(content).hexdigest(), "workflow_run": {"id": 123, "head_sha": "b" * 40}}
        request, _ = approval.read_bundle(artifact, content, self.run)
        self.assertEqual(self.request, request)
        for changed in ({**artifact, "digest": "sha256:" + "0" * 64}, {**artifact, "name": "publication-v0.2.0-123-1"},
                        {**artifact, "workflow_run": {"id": 1, "head_sha": "b" * 40}}):
            with self.assertRaises(ValueError):
                approval.read_bundle(changed, content, self.run)

    def test_pending_services_prevent_dispatch_and_public_reads_need_no_upload_tokens(self):
        request = copy.deepcopy(self.request)
        request["destinations"]["curseforge"] = False
        evidence = {"modrinth-receipts/submit.json": {"projectId": "abc123"},
                    "hangar/receipt.json": {"sourceCommit": "a" * 40, "version": "0.2.0", "namespace": "owner/Strata"}}
        with patch.object(approval, "service_json", return_value={"status": "processing"}) as read:
            self.assertFalse(approval.distributions_ready(request, evidence))
            self.assertEqual(1, read.call_count)
        with patch.object(approval, "service_json", side_effect=[{"status": "approved"}, {"visibility": "public"}]) as read:
            with patch.object(approval.hangar, "authenticate") as authenticate:
                self.assertTrue(approval.distributions_ready(request, evidence))
                authenticate.assert_not_called()
                self.assertTrue(all(len(call.args) == 1 for call in read.call_args_list))

    def test_curseforge_waits_for_approved_available_files(self):
        request = copy.deepcopy(self.request)
        request["destinations"] = {name: name == "curseforge" for name in approval.DESTINATIONS}
        evidence = {"curseforge/receipt.json": {"sourceCommit": "a" * 40, "tag": "v0.2.0", "projectId": 12,
                                               "files": {"a.jar": {"fileId": 34}}}}
        for status, available, expected in ((3, False, False), (4, False, False), (4, True, True), (10, True, True)):
            with patch.dict(os.environ, {"CURSEFORGE_API_KEY": "read-only-key"}):
                with patch.object(approval, "service_json", return_value={"data": {"fileStatus": status, "isAvailable": available}}) as read:
                    self.assertEqual(expected, approval.distributions_ready(request, evidence))
                    self.assertEqual({"x-api-key": "read-only-key"}, read.call_args.args[1])

    def test_missing_read_key_checks_accepted_ids_and_all_other_destinations(self):
        evidence = {"modrinth-receipts/submit.json": {"projectId": "abc123"},
                    "hangar/receipt.json": {"sourceCommit": "a" * 40, "version": "0.2.0", "namespace": "owner/Strata"},
                    "curseforge/receipt.json": {"sourceCommit": "a" * 40, "tag": "v0.2.0", "projectId": 12,
                                               "files": {"a.jar": {"fileId": 34}}}}
        with patch.dict(os.environ, {"CURSEFORGE_API_KEY": ""}):
            for visibility, expected in (("new", False), ("public", True)):
                with patch.object(approval, "service_json", side_effect=[{"status": "approved"}, {"visibility": visibility}]) as read:
                    self.assertEqual(expected, approval.distributions_ready(self.request, evidence))
                    self.assertEqual(2, read.call_count)
                    self.assertTrue(all("curseforge.com" not in call.args[0] for call in read.call_args_list))
            evidence["curseforge/receipt.json"]["files"]["a.jar"]["fileId"] = None
            with patch.object(approval, "service_json", return_value={"status": "approved"}):
                self.assertFalse(approval.distributions_ready(self.request, evidence))

    def test_dispatch_can_only_request_verify_and_does_not_repeat_failed_verification(self):
        with patch.object(approval.subprocess, "run") as run:
            run.return_value.returncode = 0
            approval.dispatch(self.request)
            payload = json.loads(run.call_args.kwargs["input"])
            self.assertEqual("master", payload["ref"])
            self.assertEqual("verify", payload["inputs"]["operation"])
            self.assertEqual("verify v0.2.0", payload["inputs"]["confirmation"])
            self.assertEqual("a" * 40, payload["inputs"]["source_commit"])
        verify = {"head_branch": "master", "created_at": "2026-09-24T01:00:00Z", "conclusion": "failure",
                  "display_title": "Publish verify v0.2.0 (" + "a" * 40 + ") - CurseForge true"}
        self.assertTrue(approval.verification_requested(self.request, self.run, [verify]))
        self.assertFalse(approval.verification_requested(self.request, self.run, [{**verify, "created_at": "2026-09-23T00:00:00Z"}]))
