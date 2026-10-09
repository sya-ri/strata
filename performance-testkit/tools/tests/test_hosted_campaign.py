"""Offline hostile-envelope and terminal-source controls; never instantiate an executor or launch Java."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cpu_plan
from hosted_audit import audit_job
from hosted_campaign import pack, qualification, unpack
from hosted_role import Role


class HostedCampaignTest(unittest.TestCase):
    """Exercise acceptance boundaries using explicitly synthetic controls, separate from campaign evidence."""

    def envelope(self, root, entries, binding=None):
        """Create a hostile synthetic transport whose inventory matches its raw archive bytes."""
        root.mkdir()
        inventory = {}
        with zipfile.ZipFile(root / "bundle.zip", "x") as archive:
            for name, payload, mode in entries:
                info = zipfile.ZipInfo(name)
                info.external_attr = mode << 16
                archive.writestr(info, payload)
                inventory[name] = {"sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload)}
        cpu_plan.write_new(root / "bundle.json", {"binding": binding or {"test": "synthetic"}, "files": inventory})
        cpu_plan.write_new(root / "archive.json", {"sha256": cpu_plan.digest(root / "bundle.zip")})

    def test_round_trip_complete_payload(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            source = root / "source"
            (source / "nested").mkdir(parents=True)
            (source / "nested/payload").write_bytes(b"actual control bytes\x00")
            binding = {"test": "synthetic"}
            pack(source, root / "bundle", binding)
            unpack(root / "bundle", root / "restored", binding)
            self.assertEqual((root / "restored/nested/payload").read_bytes(), (source / "nested/payload").read_bytes())

    def test_unsafe_paths_fail_before_writing(self):
        for name in ("../escape", "/absolute", "C:drive", "a\\b", "a/./b", "a//b"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as value:
                root = Path(value)
                self.envelope(root / "bundle", [(name, b"x", stat.S_IFREG)])
                with self.assertRaises(ValueError):
                    unpack(root / "bundle", root / "restored", {"test": "synthetic"})
                self.assertFalse((root / "restored").exists())

    def test_duplicate_archive_and_conflicting_parent_fail(self):
        for entries in ([('x', b'x', stat.S_IFREG), ('x', b'x', stat.S_IFREG)],
                        [('a', b'x', stat.S_IFREG), ('a/b', b'x', stat.S_IFREG)]):
            with tempfile.TemporaryDirectory() as value:
                root = Path(value)
                self.envelope(root / "bundle", entries)
                with self.assertRaises(ValueError):
                    unpack(root / "bundle", root / "restored", {"test": "synthetic"})
                self.assertFalse((root / "restored").exists())

    def test_linked_entry_fail(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            self.envelope(root / "bundle", [('link', b'outside', stat.S_IFLNK)])
            with self.assertRaises(ValueError):
                unpack(root / "bundle", root / "restored", {"test": "synthetic"})
            self.assertFalse((root / "restored").exists())

    def test_mixed_binding_and_changed_archive_fail(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            self.envelope(root / "bundle", [('x', b'x', stat.S_IFREG)])
            with self.assertRaises(ValueError):
                unpack(root / "bundle", root / "mixed", {"test": "foreign"})
            with (root / "bundle/bundle.zip").open('ab') as output:
                output.write(b'changed')
            with self.assertRaises(ValueError):
                unpack(root / "bundle", root / "changed", {"test": "synthetic"})

    def proof(self):
        """Return clearly synthetic review controls with five distinct observed profiles."""
        campaign = SimpleNamespace(name="synthetic-campaign", workspace=Path("/synthetic"), deadline_unix_ms=100)
        ready, jobs = {}, {}
        for role in Role:
            profile = {"conditions": {"physical_id": "synthetic-" + role.value}}
            ready[role.value] = {"profile": profile, "workspace": str(campaign.workspace), "sdk": {"synthetic": True}}
            proofs = {name: {"source": "SYNTHETIC CONTROL", "sha256": hashlib.sha256(b"SYNTHETIC CONTROL").hexdigest()}
                      for name in ("physical_independence", "exclusive_occupancy", "fixed_affinity", "authoritative_power_policy", "same_job_and_boot_lifetime", "permission_and_memory_disk_capacity")}
            jobs[role.value] = {"profile_sha256": cpu_plan.identity(profile), "sdk": ready[role.value]["sdk"],
                                "workspace": str(campaign.workspace), "proofs": proofs, "valid_until_unix_ms": 100}
        value = {"accepted": True, "campaign": campaign.name, "commit": "synthetic-sha", "jobs": jobs}
        return campaign, ready, value

    def test_complete_review_source_binding_control(self):
        campaign, ready, value = self.proof()
        with patch.dict('os.environ', {"GITHUB_SHA": "synthetic-sha"}):
            self.assertEqual(qualification({"raw_body": json.dumps(value), "value": value}, campaign, ready), value)

    def test_missing_changed_expired_and_shared_qualification_fail(self):
        for fault in ('missing', 'changed', 'expired', 'shared'):
            campaign, ready, value = self.proof()
            if fault == 'missing':
                del value['jobs'][Role.WORKER_1.value]['proofs']['physical_independence']
            if fault == 'changed':
                value['jobs'][Role.WORKER_1.value]['profile_sha256'] = 'foreign'
            if fault == 'expired':
                value['jobs'][Role.WORKER_1.value]['valid_until_unix_ms'] = 99
            if fault == 'shared':
                ready[Role.WORKER_2.value]['profile'] = deepcopy(ready[Role.WORKER_1.value]['profile'])
                value['jobs'][Role.WORKER_2.value]['profile_sha256'] = cpu_plan.identity(ready[Role.WORKER_2.value]['profile'])
            with self.subTest(fault=fault), patch.dict('os.environ', {"GITHUB_SHA": "synthetic-sha"}):
                with self.assertRaises(ValueError):
                    qualification({"raw_body": json.dumps(value), "value": value}, campaign, ready)

    def final_source(self):
        """Create synthetic final receipts only for parser and unknown-release rejection controls."""
        role = Role.WORKER_1
        cost = {'complete': True, 'cleanup_complete': True, 'exit_code': 0, 'cpu_seconds': 1.0}
        final = {'campaign': 'synthetic', 'role': role.value,
                 'terminal_upload_acknowledgement': {'status': 'passed', 'operation': 'upload'},
                 'late_terminal_phases': [{'phase': {'status': 'passed', 'cost': cost, 'log_sha256': hashlib.sha256(b'').hexdigest()}, 'log_text': ''}]}
        raw = json.dumps(final) + '\n'
        owner = {'scope': 'inclusive-campaign-python-sdk-jmh-owned-tree', 'complete': True, 'owned_release': 'released',
                 'cost': cost, 'log_text': raw, 'log_bytes': len(raw.encode()), 'log_sha256': hashlib.sha256(raw.encode()).hexdigest()}
        node = {'scope': 'node-action-owner-only', 'child_code': 0, 'signal': None, 'elapsed_ns': '1000', 'cpu_microseconds': {'user': 1, 'system': 1}}
        text = json.dumps({'hosted_owner_terminal': owner}) + '\n' + json.dumps(node, separators=(',', ':'))
        job = {'name': 'CPU campaign ' + role.value, 'status': 'completed', 'conclusion': 'success',
               'started_at': '2026-10-09T00:00:00Z', 'completed_at': '2026-10-09T00:01:00Z',
               'steps': [{'status': 'completed'}]}
        return job, text, owner

    def test_final_receipt_scope_control(self):
        job, text, _ = self.final_source()
        result = audit_job(job, text, Role.WORKER_1, 'synthetic')
        self.assertEqual(result['owner']['cost']['cpu_seconds'], 1.0)
        self.assertIsNone(result['platform_cpu_seconds'])

    def test_unknown_release_duplicate_and_truncated_log_fail(self):
        job, text, owner = self.final_source()
        for source in (text + '\n' + text, text.replace(owner['log_sha256'], 'foreign'),
                       text.replace('"cleanup_complete": true', '"cleanup_complete": false')):
            with self.assertRaises(ValueError):
                audit_job(job, source, Role.WORKER_1, 'synthetic')


if __name__ == '__main__':
    unittest.main()
