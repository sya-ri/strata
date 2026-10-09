"""One fixed five-job blit campaign using GitHub artifacts and the existing CPU driver.

This adapter holds its original job through three complete serial/parallel pairs.
It never provisions hosts, changes measurements or substitutes synthetic qualification.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import signal
import stat
import subprocess
import sys
import time
import uuid
import zipfile

import cpu_plan
from cpu_host import observe, run_process
from cpu_mode import Mode
from cpu_phase import Phase
from cpu_scheduling import Scheduling
from cpu_outcome import Outcome
from hosted_prepare import prepare
from hosted_role import Role


def pack(source, destination, binding):
    """Inventory every regular payload file, rejecting links and excessive retained evidence."""
    source, destination = Path(source), Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    files = {}
    total = 0
    for path in sorted(source.rglob("*")):
        cpu_plan.require(not path.is_symlink(), "Linked campaign payload")
        cpu_plan.require(path.is_dir() or path.is_file(), "Unsupported campaign payload kind")
        if path.is_file():
            name = path.relative_to(source).as_posix()
            total += path.stat().st_size
            cpu_plan.require(len(files) < 20000 and total <= 2 * 1024 ** 3, "Campaign bundle exceeds bound; never truncate")
            files[name] = {"sha256": cpu_plan.digest(path), "bytes": path.stat().st_size}
    cpu_plan.require(files, "Empty campaign bundle")
    cpu_plan.write_new(destination / "bundle.json", {"binding": binding, "files": files})
    with zipfile.ZipFile(destination / "bundle.zip", "x", compression=zipfile.ZIP_STORED) as archive:
        for name in files:
            archive.write(source / name, name)
    cpu_plan.write_new(destination / "archive.json", {"sha256": cpu_plan.digest(destination / "bundle.zip")})


def unpack(source, destination, binding):
    """Reject duplicate, missing, mixed, linked and traversing archives before writing any byte."""
    source, destination = Path(source), Path(destination)
    cpu_plan.require({path.name for path in source.iterdir()} == {"bundle.json", "bundle.zip", "archive.json"}, "Unexpected SDK payload files")
    manifest = cpu_plan.document(source / "bundle.json")
    cpu_plan.require(manifest["binding"] == binding and manifest["files"], "Mixed campaign/role/round/source binding")
    cpu_plan.require(cpu_plan.digest(source / "bundle.zip") == cpu_plan.document(source / "archive.json")["sha256"], "Changed inner bundle bytes")
    with zipfile.ZipFile(source / "bundle.zip") as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        cpu_plan.require(len(entries) <= 20000 and len(names) == len(set(names)) and set(names) == set(manifest["files"]), "Duplicate, omitted or extra bundle file")
        cpu_plan.require(sum(entry.file_size for entry in entries) <= 2 * 1024 ** 3, "Oversized received bundle")
        for entry in entries:
            parts = PurePosixPath(entry.filename).parts
            cpu_plan.require(parts and not entry.filename.startswith("/") and ".." not in parts and "\\" not in entry.filename and ":" not in entry.filename, "Unsafe received path")
            cpu_plan.require(PurePosixPath(entry.filename).as_posix() == entry.filename
                             and not any(parent.as_posix() in manifest["files"] for parent in PurePosixPath(entry.filename).parents),
                             "Noncanonical or conflicting received path")
            cpu_plan.require(not stat.S_ISLNK(entry.external_attr >> 16) and not entry.is_dir(), "Linked or unexpected directory entry")
            expected = manifest["files"][entry.filename]
            with archive.open(entry) as input_file:
                actual_hash = hashlib.file_digest(input_file, "sha256").hexdigest()
            cpu_plan.require(entry.file_size == expected["bytes"] and actual_hash == expected["sha256"], "Received file size/hash mismatch")
        destination.mkdir(parents=True, exist_ok=False)
        for entry in entries:
            path = destination / entry.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("xb") as output:
                with archive.open(entry) as input_file:
                    shutil.copyfileobj(input_file, output)


def qualification(value, campaign, ready):
    """Require the authoritative reviewer to bind all actual profiles and preserved proof sources."""
    decoded = json.loads(value["raw_body"], object_pairs_hook=cpu_plan.unique_object,
                         parse_constant=lambda token: cpu_plan.require(False, "Non-finite qualification source"))
    cpu_plan.require(decoded == value["value"], "Qualification adapter changed original source")
    value = decoded
    cpu_plan.require(value["accepted"] is True and value["campaign"] == campaign.name
                     and value["commit"] == os.environ["GITHUB_SHA"], "Missing or foreign reviewed qualification")
    expected = {role.value for role in Role}
    cpu_plan.require(set(value["jobs"]) == expected and len(value["jobs"]) == 5, "Incomplete five-job qualification")
    proofs = {"physical_independence", "exclusive_occupancy", "fixed_affinity", "authoritative_power_policy", "same_job_and_boot_lifetime", "permission_and_memory_disk_capacity"}
    for role in Role:
        approval = value["jobs"][role.value]
        actual = ready[role.value]
        cpu_plan.require(approval["profile_sha256"] == cpu_plan.identity(actual["profile"])
                         and approval["sdk"] == actual["sdk"] and approval["workspace"] == actual["workspace"]
                         and actual["workspace"] == str(campaign.workspace)
                         and set(approval["proofs"]) == proofs, "Qualification changed observed job/profile/SDK/source")
        for proof in approval["proofs"].values():
            # Sources travel in the qualification comment, whose immutable GitHub URL is retained.
            cpu_plan.require(isinstance(proof["source"], str) and proof["source"].strip()
                             and hashlib.sha256(proof["source"].encode()).hexdigest() == proof["sha256"], "Missing authoritative qualification proof bytes")
        cpu_plan.require(campaign.deadline_unix_ms <= approval["valid_until_unix_ms"], "Qualification expires before held job lifetime")
    identities = [ready[role.value]["profile"]["conditions"]["physical_id"] for role in Role]
    cpu_plan.require(len(identities) == len(set(identities)), "Shared actual physical identity")
    return value


class Campaign:
    """Own this job's immutable transactions, deadline, same-boot observations and terminal ledger."""

    def __init__(self):
        self.role = Role(os.environ["INPUT_ROLE"])
        self.workspace = Path(os.environ["GITHUB_WORKSPACE"]).resolve()
        actual_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.workspace, text=True).strip()
        cpu_plan.require(actual_head == os.environ["GITHUB_SHA"], "Checked-out campaign source differs from actual run")
        self.name = "cpu-" + os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"] + "-" + os.environ["GITHUB_SHA"][:12]
        self.root = self.workspace / ".cpu" / self.name
        self.root.mkdir(parents=True, exist_ok=False)
        self.java = str(Path(os.environ["JAVA_HOME_17_X64"]) / "bin/java")
        self.started_ns = time.monotonic_ns()
        self.started_cpu_ns = time.process_time_ns()
        self.deadline = time.monotonic() + 330 * 60
        self.deadline_unix_ms = int(time.time() * 1000) + 330 * 60 * 1000
        self.ledger = []
        self.sent_results = set()
        self.commands = {role.value: 0 for role in Role}
        self.stopping = False
        self.profile = None
        self.profile_failure = None
        try:
            self.profile = observe(self.java)
        except Exception as error:
            self.profile_failure = type(error).__name__ + ": " + str(error)
        self.sdk = {}
        for name, main in (("upload", "dist/upload/index.js"), ("download", "dist/index.js")):
            root = self.workspace / ".cpu-sdk" / name
            commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
            self.sdk[name] = {"commit": commit, "main_sha256": cpu_plan.digest(root / main), "metadata_sha256": cpu_plan.digest(root / "action.yml")}
        cpu_plan.write_new(self.root / "start.json", {"role": self.role.value, "campaign": self.name, "started_ns": self.started_ns, "started_unix_ms": int(time.time() * 1000), "profile": self.profile, "profile_failure": self.profile_failure, "sdk": self.sdk, "deadline_unix_ms": self.deadline_unix_ms})

    @staticmethod
    def read(path):
        """Use the driver's strict bounded JSON adapter."""
        return cpu_plan.document(path)

    def check(self):
        """Prevent budget, host, boot, power or occupancy changes from entering a new operation."""
        cpu_plan.require(time.monotonic() < self.deadline, "Campaign deadline exhausted; scope cannot be shortened")
        if self.profile is not None and not self.stopping:
            cpu_plan.require(observe(self.java)["conditions"] == self.profile["conditions"], "Held executor conditions changed")

    def command(self, purpose, command, cwd=None, artifacts=None, transfer=None):
        """Measure the exact owned command once and retain failure sources without automatic transport retry."""
        self.check()
        phase_root = self.root / "ledger" / (str(len(self.ledger)) + "-" + purpose)
        spec = phase_root.with_suffix(".spec.json")
        cpu_plan.write_new(spec, {"phase": Phase.TRANSFER.value if transfer else Phase.PREPARATION.value,
                                "binding": transfer or {"campaign": self.name, "role": self.role.value, "purpose": purpose},
                                "command": command, "workspace": str(cwd or self.workspace), "artifacts": [str(path) for path in (artifacts or [spec])]})
        phase = cpu_plan.measure_phase(spec, phase_root)
        self.ledger.append({"purpose": purpose, "phase": str(phase_root / "phase.json"), "sha256": cpu_plan.digest(phase_root / "phase.json")})
        cpu_plan.require(Outcome(phase["status"]) is Outcome.PASSED, "Owned campaign operation failed: " + purpose)
        return phase_root / "phase.json"

    def binding(self, kind, role, sequence):
        """Bind a unique immutable envelope to the exact repository/run attempt/source/role/sequence."""
        return {"repository": os.environ["GITHUB_REPOSITORY"], "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"], "commit": os.environ["GITHUB_SHA"], "campaign": self.name, "kind": kind, "role": role.value, "sequence": sequence}

    def transport(self, operation, name, directory, purpose, transfer=None, artifacts=None):
        """Include API polling, official SDK descendants, acknowledgements and failure logs in actual costs."""
        receipt = self.root / "transactions" / (purpose + "-" + str(uuid.uuid4()) + ".json")
        receipt.parent.mkdir(parents=True, exist_ok=True)
        command = [os.environ["CPU_NODE"], str(Path(os.environ["CPU_ACTION"]) / "transfer.mjs"), operation, name, str(directory), str(receipt), str(self.deadline_unix_ms)]
        phase = self.command(purpose, command, artifacts=[receipt] + (artifacts or []), transfer=transfer)
        cpu_plan.require(Outcome(self.read(receipt)["status"]) is Outcome.PASSED, "Missing actual SDK acknowledgement")
        return phase

    def send(self, kind, role, sequence, source):
        """Upload one complete immutable envelope without overwriting prior attempts."""
        binding = self.binding(kind, role, sequence)
        name = "_".join((self.name, kind, role.value, str(sequence)))
        directory = self.root / "send" / name
        request = self.root / "pack-requests" / (name + ".json")
        cpu_plan.write_new(request, {"source": str(source), "destination": str(directory), "binding": binding})
        self.command("pack-" + name, [sys.executable, "-B", __file__, "pack", str(request)],
                     artifacts=[directory / "bundle.json", directory / "archive.json", directory / "bundle.zip"])
        self.transport("upload", name, directory, "upload-" + name)
        if kind == "result":
            self.sent_results.add(Path(source).parents[1])

    def receive(self, kind, role, sequence, transfer=None):
        """Download by one exact artifact identity, reject envelope/file drift and retain restoration costs."""
        binding = self.binding(kind, role, sequence)
        name = "_".join((self.name, kind, role.value, str(sequence)))
        directory = self.root / "receive" / name
        result = self.root / "unpacked" / name
        receipt = self.root / "transactions" / ("download-" + name + ".json")
        receipt.parent.mkdir(parents=True, exist_ok=True)
        request = self.root / "return-specs" / (name + ".json")
        output = Path(transfer["output"]) if transfer else None
        cpu_plan.write_new(request, {"name": name, "directory": str(directory), "result": str(result),
                                     "receipt": str(receipt), "deadline": self.deadline_unix_ms,
                                     "binding": binding, "output": str(output) if output else None})
        command = [sys.executable, "-B", __file__, "receive", str(request)]
        artifacts = [receipt, directory / "bundle.json", directory / "archive.json"]
        if output:
            # SDK polling/download, envelope validation and full return restoration share this actual phase.
            phase_spec = request.with_suffix(".phase.json")
            cpu_plan.write_new(phase_spec, {"phase": Phase.TRANSFER.value,
                                           "binding": {key: value for key, value in transfer.items() if key != "output"},
                                           "command": command, "workspace": str(self.workspace),
                                           "artifacts": [str(path) for path in artifacts + [output / "shard.json"]]})
            phase_root = output.parents[2] / "transfers" / name
            outcome = cpu_plan.measure_phase(phase_spec, phase_root)
            self.ledger.append({"purpose": "download-return", "phase": str(phase_root / "phase.json"), "sha256": cpu_plan.digest(phase_root / "phase.json")})
            cpu_plan.require(Outcome(outcome["status"]) is Outcome.PASSED, "Failed complete shard SDK return")
        else:
            self.command("download-" + name, command, artifacts=artifacts)
        return result

    def worker(self):
        """Keep this actual worker job and boot through all granted whole attempts, without a worker pool."""
        ready = self.root / "ready"
        ready.mkdir()
        cpu_plan.write_new(ready / "ready.json", {"role": self.role.value, "workspace": str(self.workspace), "profile": self.profile, "profile_failure": self.profile_failure, "sdk": self.sdk, "deadline_unix_ms": self.deadline_unix_ms})
        self.send("ready", self.role, 0, ready)
        cpu_plan.require(self.profile is not None, "Worker qualification profile unavailable; no Java collector starts")
        prepared = self.receive("prepared", self.role, 0)
        for name in ("prepared", "plan"):
            shutil.copytree(prepared / name, self.root / name)
        plan = self.root / "plan"
        sequence = 0
        while True:
            received = self.receive("command", self.role, sequence)
            instruction = self.read(received / "instruction.json")
            if instruction["terminal"] is True:
                break
            attempt_root = self.root / "worker-attempts" / instruction["whole_attempt"]
            shutil.copytree(received / "attempt", attempt_root)
            cpu_plan.require(self.read(attempt_root / "attempt.json")["whole_attempt"] == instruction["whole_attempt"], "Mixed worker instruction")
            try:
                cpu_plan.run_shard(plan, attempt_root, self.role.shard, self.workspace)
            except Exception:
                # run_shard retains its partial immutable failed source; it must return as a failed whole attempt.
                cpu_plan.require((attempt_root / "shards" / self.role.shard / "shard.json").is_file(), "Missing failed shard/unknown ownership")
                self.send("result", self.role, sequence, attempt_root / "shards" / self.role.shard)
                # Preserve the source, then quarantine this job; an absent final child receipt is never reusable.
                raise
            self.send("result", self.role, sequence, attempt_root / "shards" / self.role.shard)
            sequence += 1

    def coordinator(self, spec):
        """Qualify actual five-job sources, freeze once and collect the complete fixed three wall pairs."""
        cpu_plan.require(self.profile is not None, "Coordinator observer unavailable; no campaign preparation starts")
        ready = {self.role.value: {"role": self.role.value, "workspace": str(self.workspace), "profile": self.profile, "sdk": self.sdk, "deadline_unix_ms": self.deadline_unix_ms}}
        for role in list(Role)[1:]:
            ready[role.value] = self.read(self.receive("ready", role, 0) / "ready.json")
            cpu_plan.require(ready[role.value]["role"] == role.value and ready[role.value]["sdk"] == self.sdk, "Mixed ready worker or SDK bytes")
            cpu_plan.require(ready[role.value]["profile"] is not None, "Worker profile unavailable; qualification is incomplete")
        cpu_plan.write_new(self.root / "actual-ready-profiles.json", ready)
        proof_root = self.root / "qualification"
        self.transport("qualification", self.name, proof_root, "reviewed-qualification")
        reviewed = qualification(self.read(proof_root / "qualification.json"), self, ready)
        for sdk in ("upload", "download"):
            archive = self.root / "sdk-sources" / (sdk + ".zip")
            archive.parent.mkdir(parents=True, exist_ok=True)
            self.command("preserve-sdk-" + sdk, ["git", "-C", str(self.workspace / ".cpu-sdk" / sdk), "archive", "--format=zip", "--output=" + str(archive), "HEAD"], artifacts=[archive])
        executors = []
        for role in list(Role)[1:]:
            target = proof_root / role.value
            target.mkdir()
            cpu_plan.write_new(target / "profile.json", ready[role.value]["profile"])
            cpu_plan.write_new(target / "qualification.json", reviewed["jobs"][role.value])
            executors.append({"id": role.executor, "profile": str(target / "profile.json"), "qualification": str(target / "qualification.json")})
        corpus, comparator = prepare(self, spec)
        plan = self.root / "plan"
        request = self.root / "freeze-request.json"
        preparation = [entry["phase"] for entry in self.ledger if entry["purpose"].startswith("admit-")]
        cpu_plan.write_new(request, {"corpora": [corpus], "executors": executors, "shards": spec["shards"], "interleaving": spec["interleaving"], "max_attempts": 2, "comparator": {"java": self.java, "classpath": comparator}, "preparation_receipts": preparation})
        cpu_plan.freeze(request, plan)
        bundle = self.root / "distribution"
        bundle.mkdir()
        for name in ("prepared", "plan"):
            shutil.copytree(self.root / name, bundle / name)
        for role in list(Role)[1:]:
            self.send("prepared", role, 0, bundle)
        comparisons = []
        for repetition in range(3):
            collection = self.root / ("collection-" + str(repetition))
            for scheduling in Scheduling:
                for retry in range(2):
                    self.check()
                    attempt = cpu_plan.begin(plan, collection, scheduling.value)
                    pending = []
                    for role in list(Role)[1:]:
                        cpu_plan.grant(plan, attempt, role.shard)
                        sequence = self.commands[role.value]
                        source = self.root / "instructions" / role.value / str(sequence)
                        source.mkdir(parents=True)
                        cpu_plan.write_new(source / "instruction.json", {"terminal": False, "whole_attempt": self.read(attempt / "attempt.json")["whole_attempt"]})
                        (source / "attempt/permits").mkdir(parents=True)
                        shutil.copyfile(attempt / "attempt.json", source / "attempt/attempt.json")
                        shutil.copyfile(attempt / "permits" / (role.shard + ".json"), source / "attempt/permits" / (role.shard + ".json"))
                        self.send("command", role, sequence, source)
                        self.commands[role.value] += 1
                        pending.append((role, sequence))
                        if scheduling is Scheduling.SERIAL:
                            self.returned(plan, attempt, role, sequence)
                    if scheduling is Scheduling.PARALLEL:
                        for role, sequence in pending:
                            self.returned(plan, attempt, role, sequence)
                    outcome = cpu_plan.finish(plan, attempt)
                    if Outcome(outcome["status"]) is Outcome.PASSED:
                        break
                    self.assert_retryable_workers(attempt)
                    cpu_plan.require(retry == 0, "Both whole attempts failed; no partial adoption")
            output = self.root / "comparisons" / ("collection-" + str(repetition) + ".json")
            comparisons.append(cpu_plan.compare_collection(plan, collection, output))
        cpu_plan.write_new(self.root / "campaign-comparisons.json", {"comparisons": comparisons, "qualification": reviewed, "all_runtime_or_native_acceptance": False})
        terminal_sources = []
        for role in list(Role)[1:]:
            source = self.root / "stop" / role.value
            source.mkdir(parents=True)
            cpu_plan.write_new(source / "instruction.json", {"terminal": True})
            self.send("command", role, self.commands[role.value], source)
            returned = self.receive("terminal", role, 0)
            terminal = self.read(returned / "terminal.json")
            cpu_plan.require(terminal["role"] == role.value and terminal["campaign"] == self.name
                             and Outcome(terminal["status"]) is Outcome.PASSED
                             and terminal["profile"] == ready[role.value]["profile"]
                             and terminal["sdk"] == self.sdk, "Failed, mixed or changed worker terminal")
            terminal_sources.append(returned)
        self.audit(terminal_sources)

    @staticmethod
    def assert_retryable_workers(attempt):
        """After saving a failed whole outcome, reject reuse of any failed or incompletely released worker."""
        for role in list(Role)[1:]:
            shard = cpu_plan.document(Path(attempt) / "shards" / role.shard / "shard.json")
            cpu_plan.require(Outcome(shard["status"]) is Outcome.PASSED and shard["slots"]
                             and all(slot["cost"].get("complete") is True
                                     and slot["cost"].get("cleanup_complete") is True
                                     and slot["cost"].get("exit_code") == 0 for slot in shard["slots"]),
                             "Fixed worker failed or ownership is unknown; retained whole failure is terminal")

    def audit(self, worker_sources):
        """Audit every preserved transaction and global measurement identity, keeping later terminal costs separate."""
        transactions = {}
        child_cpu_seconds = 0.0
        controller_cpu_ns = time.process_time_ns() - self.started_cpu_ns
        sources = [self.root] + worker_sources
        phase_ids = set()
        for source in sources:
            terminal = None if source == self.root else self.read(source / "terminal.json")
            ledger = self.ledger if terminal is None else terminal["ledger"]
            if terminal is not None:
                controller_cpu_ns += terminal["controller_only_cpu_ns"]
            for record in ledger:
                relative = Path(record["phase"]).relative_to(self.root)
                path = source / relative
                phase = self.read(path)
                cpu_plan.require(cpu_plan.digest(path) == record["sha256"] and phase["phase_id"] not in phase_ids
                                 and Outcome(phase["status"]) is Outcome.PASSED, "Changed, duplicate or failed campaign phase")
                phase_ids.add(phase["phase_id"])
                cpu_plan.require(phase["cost"]["complete"] and phase["cost"]["cleanup_complete"]
                                 and phase["cost"]["exit_code"] == 0 and phase["cost"]["cpu_seconds"] is not None,
                                 "Missing actual owned phase CPU/release")
                cpu_plan.require(cpu_plan.digest(path.parent / "command.log") == phase["log_sha256"], "Changed full SDK/command log")
                child_cpu_seconds += phase["cost"]["cpu_seconds"]
                for artifact, expected in phase["artifacts"].items():
                    original = Path(artifact)
                    if self.root in original.parents:
                        restored = source / original.relative_to(self.root)
                        if not restored.is_file() and original.relative_to(self.root).parts[0] in ("send", "receive"):
                            envelope, *within = original.relative_to(self.root).parts[1:]
                            candidates = [self.root / direction / envelope / Path(*within) for direction in ("send", "receive")]
                            restored = next((path for path in candidates if path.is_file()), restored)
                        cpu_plan.require(cpu_plan.digest(restored) == expected["sha256"] and restored.stat().st_size == expected["bytes"], "Missing actual phase acknowledgement or artifact bytes")
            for path in (source / "transactions").glob("*.json"):
                value = self.read(path)
                for poll in value["polling"]:
                    body = Path(str(path) + ".sources") / (poll["sha256"] + ".json")
                    cpu_plan.require(body.stat().st_size == poll["bytes"] and cpu_plan.digest(body) == poll["sha256"],
                                     "Missing or changed actual API response source")
                if value["operation"] not in ("upload", "download"):
                    continue
                key = (value["operation"], value["name"])
                cpu_plan.require(key not in transactions and Outcome(value["status"]) is Outcome.PASSED
                                 and value["repository"] == os.environ["GITHUB_REPOSITORY"]
                                 and value["run"] == os.environ["GITHUB_RUN_ID"], "Duplicate, failed or mixed SDK transaction")
                transactions[key] = value
        for (operation, name), download in transactions.items():
            if operation == "download" and "_terminal_" not in name:
                upload = transactions.get(("upload", name))
                cpu_plan.require(upload is not None and int(upload["outputs"]["artifact-id"]) == download["artifact"]["id"]
                                 and "sha256:" + upload["outputs"]["artifact-digest"] == download["artifact"]["digest"],
                                 "Missing/mixed actual upload acknowledgement for a downloaded bundle")
        seen = {key: set() for key in ("whole_attempt", "shard_attempt", "run_id", "fork_id", "iteration_id")}
        for path in sorted(self.root.glob("collection-*/*/attempt.json")):
            value = self.read(path)
            cpu_plan.require(value["whole_attempt"] not in seen["whole_attempt"], "Reused whole attempt across wall pairs")
            seen["whole_attempt"].add(value["whole_attempt"])
            ids = list(value["shard_attempts"].values())
            cpu_plan.require(len(ids) == 4 and len(set(ids)) == 4 and not seen["shard_attempt"].intersection(ids), "Reused cross-pair shard attempt")
            seen["shard_attempt"].update(ids)
        for path in sorted(self.root.glob("collection-*/*/shards/*/*/receipt.json")):
            value = self.read(path)
            cpu_plan.require(value["run_id"] not in seen["run_id"], "Reused measured invocation across wall pairs/retries")
            seen["run_id"].add(value["run_id"])
        for collection in sorted(self.root.glob("collection-*")):
            for attempt in sorted(collection.glob("*/attempt.json")):
                forks = set()
                for path in sorted(attempt.parent.glob("shards/*/*/cpu-forks/*.json")):
                    value = self.read(path)
                    cpu_plan.require(value["fork_id"] not in seen["fork_id"] and value["iteration_id"] not in seen["iteration_id"], "Reused measured fork/iteration across wall pairs/retries")
                    forks.add(value["fork_id"])
                    seen["iteration_id"].add(value["iteration_id"])
                seen["fork_id"].update(forks)
        cpu_plan.require(len(seen["whole_attempt"]) in range(6, 13), "Incomplete three serial/parallel pairs")
        cpu_plan.write_new(self.root / "all-cost-audit.json", {"campaign": self.name, "preserved_transactions": [{"operation": key[0], "name": key[1], "receipt": value} for key, value in sorted(transactions.items())],
                            "controller_only_cpu_ns": controller_cpu_ns, "owned_external_phase_cpu_seconds": child_cpu_seconds,
                            "measurement_process_cpu": "Preserved separately in canonical whole outcomes; not added twice to phase CPU",
                            "whole_attempt_count": len(seen["whole_attempt"]), "actual_invocation_count": len(seen["run_id"]),
                            "actual_iteration_count": len(seen["iteration_id"]), "sdk_internal_retries": "Actual SDK logs and inclusive owned command costs; an unavailable exact internal retry count is unknown",
                            "later_terminal_upload_costs": "Durable final job log receipts, outside their immutable bundles",
                            "service_setup_queue_and_release": "Final workflow job/step metadata required; not inferred from owner CPU",
                            "platform_cpu_seconds": None, "actual_runtime_or_native_acceptance": False})

    def abort(self, failure):
        """Publish one best-effort stop envelope so polling workers release without waiting for the hard limit."""
        self.stopping = True
        source = self.root / "abort-source"
        source.mkdir()
        cpu_plan.write_new(source / "failure.json", {"campaign": self.name, "failure": failure})
        self.send("abort", Role.COORDINATOR, 0, source)

    def returned(self, plan, attempt, role, sequence):
        """Restore one complete source and acknowledge it before the next serial grant."""
        context = self.read(attempt / "attempt.json")
        self.receive("result", role, sequence, {"plan_sha256": context["plan_sha256"], "scheduling": context["scheduling"], "whole_attempt": context["whole_attempt"], "shard_id": role.shard, "output": str(attempt / "shards" / role.shard)})
        cpu_plan.acknowledge(plan, attempt, role.shard)

    def terminal(self, successful, failure):
        """Preserve this owner's CPU/clock/attempt ledger and emit the later upload receipt separately."""
        report = self.root / "terminal-source"
        report.mkdir()
        cpu_plan.write_new(report / "terminal.json", {"role": self.role.value, "campaign": self.name, "status": "passed" if successful else "failed", "failure": failure, "profile": self.profile, "sdk": self.sdk, "ledger": self.ledger, "controller_only_cpu_ns": time.process_time_ns() - self.started_cpu_ns, "controller_elapsed_ns": time.monotonic_ns() - self.started_ns, "deadline_unix_ms": self.deadline_unix_ms, "platform_setup_and_release_cpu": None, "service_job_completion_metadata": "Pending read-only final workflow job audit", "acceptance": False})
        for name in ("ledger", "transactions", "comparisons", "return-specs", "sdk-sources"):
            if (self.root / name).exists():
                shutil.copytree(self.root / name, report / name)
        if self.role is Role.COORDINATOR:
            for path in list(self.root.glob("collection-*")) + [self.root / "plan", self.root / "prepared", self.root / "qualification"]:
                if path.exists():
                    shutil.copytree(path, report / path.name)
        else:
            for path in (self.root / "worker-attempts").glob("*"):
                if path not in self.sent_results:
                    shutil.copytree(path, report / "unsent-results" / path.name)
        # Keep the actual received envelope/inventory hashes without reuploading every duplicate archive.
        for root in (self.root / "receive").glob("*"):
            for name in ("bundle.json", "archive.json"):
                if (root / name).is_file():
                    target = report / "received-manifests" / root.name / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(root / name, target)
        for name in ("start.json", "campaign-comparisons.json", "all-cost-audit.json", "actual-ready-profiles.json"):
            if (self.root / name).is_file():
                shutil.copyfile(self.root / name, report / name)
        first_late_phase = len(self.ledger)
        self.send("terminal", self.role, 0, report)
        # The immutable bundle cannot contain this later transfer's receipt.
        phase = self.read(Path(self.ledger[-1]["phase"]))
        acknowledgement = self.read(Path(next(iter(phase["artifacts"]))))
        late_phases = []
        for record in self.ledger[first_late_phase:]:
            path = Path(record["phase"])
            late_phases.append({"phase": self.read(path), "log_text": (path.parent / "command.log").read_text()})
        print(cpu_plan.encoded({"late_terminal_phases": late_phases, "terminal_upload_phase": phase, "terminal_upload_acknowledgement": acknowledgement, "campaign": self.name, "role": self.role.value}).decode(), flush=True)


def main():
    """Restore only explicit worker returns, or run the one source-reviewed opt-in hosted campaign."""
    if len(sys.argv) == 3 and sys.argv[1] in ("pack", "receive"):
        request = cpu_plan.document(sys.argv[2])
        if sys.argv[1] == "pack":
            pack(request["source"], request["destination"], request["binding"])
        else:
            command = [os.environ["CPU_NODE"], str(Path(os.environ["CPU_ACTION"]) / "transfer.mjs"), "download",
                       request["name"], request["directory"], request["receipt"], str(request["deadline"])]
            subprocess.run(command, check=True)
            unpack(request["directory"], request["result"], request["binding"])
            if request["output"]:
                shutil.copytree(request["result"], request["output"])
        return
    if len(sys.argv) == 2 and sys.argv[1] == "own":
        def stopped(number, frame):
            raise KeyboardInterrupt("Hosted owner interrupted")
        signal.signal(signal.SIGTERM, stopped)
        signal.signal(signal.SIGINT, stopped)
        owner = Path(os.environ["GITHUB_WORKSPACE"]) / "build/cpu-campaign-owner" / os.environ["GITHUB_RUN_ID"] / os.environ["INPUT_ROLE"]
        owner.mkdir(parents=True, exist_ok=False)
        terminal = {"scope": "inclusive-campaign-python-sdk-jmh-owned-tree", "pid": os.getpid(), "complete": False, "owned_release": "unknown"}
        try:
            with (owner / "owned-command.log").open("xb") as log:
                terminal["cost"] = run_process([sys.executable, "-B", __file__], Path(os.environ["GITHUB_WORKSPACE"]), log)
            terminal["complete"] = terminal["cost"]["complete"] and terminal["cost"]["cleanup_complete"]
            terminal["owned_release"] = "released" if terminal["complete"] else "unknown"
        except BaseException as error:
            terminal["failure"] = type(error).__name__ + ": " + str(error)
        finally:
            log_path = owner / "owned-command.log"
            if log_path.is_file():
                terminal["log_sha256"] = cpu_plan.digest(log_path)
                terminal["log_bytes"] = log_path.stat().st_size
                if terminal["log_bytes"] <= 16 * 1024 * 1024:
                    terminal["log_text"] = log_path.read_text(encoding="utf-8")
                else:
                    terminal.update(complete=False, failure="Owned terminal log exceeds complete retention bound; raw file retained without truncation")
            cpu_plan.write_new(owner / "terminal.json", terminal)
            # Owned-tree receipt follows the child's final immutable upload; it belongs to the durable owner log.
            print(cpu_plan.encoded({"hosted_owner_terminal": terminal}).decode(), flush=True)
        if not terminal["complete"] or terminal["cost"]["exit_code"] != 0:
            raise SystemExit(1)
        return
    campaign = None
    successful = False
    failure = None
    try:
        campaign = Campaign()
        spec = campaign.read(Path(__file__).with_name("hosted-blit45.json"))
        if campaign.role is Role.COORDINATOR:
            campaign.coordinator(spec)
        else:
            campaign.worker()
        successful = True
    except BaseException as error:
        failure = type(error).__name__ + ": " + str(error)
        raise
    finally:
        if campaign is not None:
            if not successful and campaign.role is Role.COORDINATOR:
                try:
                    campaign.abort(failure)
                except BaseException as error:
                    print("Best-effort abort failed: " + str(error), flush=True)
            campaign.terminal(successful, failure)


if __name__ == "__main__":
    main()
