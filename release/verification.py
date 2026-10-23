#!/usr/bin/env python3
"""Retain revision-bound release verification, diagnostics and fail-closed signing gates."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from zipfile import ZipFile


FULL_CHECK = ["check", "-x", ":integration:docs:check", "koverHtmlReport", "koverXmlReport", "-Pkover",
              "-Pstrata.webDemoInstallSystemDependencies=true"]
EVIDENCE = Path("build/release-verification")


def require(condition, message):
    """Stop on absent or contradictory evidence rather than guessing success."""
    if not condition:
        raise ValueError(message)


def write_json(path, value):
    """Persist diagnostics atomically enough to survive a later phase failure."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def digest(path):
    """Hash retained bytes without retaining large reports in memory."""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def redact(text):
    """Remove credential environment values and private keys from retained console text."""
    for name, value in os.environ.items():
        if value and re.search(r"TOKEN|PASSWORD|SECRET|SIGNING.*KEY", name, re.IGNORECASE):
            text = text.replace(value, "[REDACTED]")
            for line in value.splitlines():
                if line:
                    text = text.replace(line, "[REDACTED]")
    text = re.sub(r"-----BEGIN PGP PRIVATE KEY BLOCK-----.*?-----END PGP PRIVATE KEY BLOCK-----",
                  "[REDACTED PRIVATE KEY]", text, flags=re.DOTALL)
    return re.sub(r"(?i)(authorization\s*[:=]\s*|https?://)[^\s/@]+:[^\s/@]+@?",
                  r"\1[REDACTED]", text)


def identity(product):
    """Bind receipts to the actual checkouts and the source admission decision."""
    product_sha = subprocess.check_output(["git", "-C", str(product), "rev-parse", "HEAD"], text=True).strip()
    controller_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    require(product_sha == os.environ["SOURCE_COMMIT"], "Product checkout differs from the selected commit.")
    require(controller_sha == os.environ["GITHUB_SHA"], "Controller checkout differs from this run.")
    require(not subprocess.check_output(["git", "-C", str(product), "status", "--porcelain", "--untracked-files=no"]),
            "Selected product sources were modified during verification.")
    scope = os.environ["RELEASE_SCOPE"]
    require(scope in ("production", "rehearsal"), "Unknown release verification scope.")
    tag_object = os.environ.get("TAG_OBJECT", "")
    require(scope != "production" or re.fullmatch(r"[0-9a-f]{40}", tag_object), "Signed tag identity is missing.")
    return dict(schemaVersion=1, scope=scope, sourceCommit=product_sha, controllerCommit=controller_sha,
                tagObject=tag_object, runId=int(os.environ["GITHUB_RUN_ID"]),
                runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]))


def runtime_profile(product):
    """Record actual installed toolchains and runner image for matched rehearsal comparisons."""
    homes = {value for name, value in os.environ.items() if name == "JAVA_HOME" or re.fullmatch(r"JAVA_HOME_[0-9]+_X64", name)}
    toolchains = []
    for home in sorted(homes):
        executable = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        result = subprocess.run([str(executable), "-version"], capture_output=True, check=True)
        toolchains.append((result.stdout + result.stderr).decode("utf-8", errors="replace").strip())
    return dict(javaVersions=sorted(set(toolchains)), logicalProcessors=os.cpu_count(),
                runner={name: os.environ.get(name) for name in ("RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion")},
                buildInputs={name: digest(product / name) for name in
                             ("gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties")})


def classify(text):
    """Classify only evidenced failures; infrastructure-looking text never enables retries."""
    if re.search(r"Compilation error|Unresolved reference|compile.*FAILED|AssertionError|tests completed.*failed|quality gate", text, re.IGNORECASE):
        return "deterministic verification failure"
    if re.search(r"checksum.*differ|signature.*invalid|conflict", text, re.IGNORECASE):
        return "integrity or publication conflict"
    if re.search(r"timed out|HTTP.*(?:429|502|503|504)|Connection reset|Temporary failure", text, re.IGNORECASE):
        return "possible transient infrastructure failure; inspect the cause before retrying"
    return "unclassified failure; inspect retained diagnostics"


def phase(name, product, command, output=EVIDENCE):
    """Run one real phase once, preserving its exit status and sanitized complete console."""
    require(re.fullmatch(r"[a-z][a-z-]*", name), "Unsafe phase name.")
    output.mkdir(parents=True, exist_ok=True)
    record = dict(identity(product), phase=name, command=command, startedAt=datetime.now(timezone.utc).isoformat())
    write_json(output / f"{name}.json", record)
    started = time.monotonic()
    with (output / f"{name}.log").open("w", encoding="utf-8") as log:
        try:
            with subprocess.Popen(command, cwd=product, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
                for line in process.stdout:
                    text = redact(line.decode("utf-8", errors="replace"))
                    log.write(text)
                    log.flush()
                    print(text, end="", flush=True)
                code = process.wait()
        except OSError as error:
            code = 127
            log.write(redact(str(error)))
    text = (output / f"{name}.log").read_text(encoding="utf-8")
    record.update(exitCode=code, elapsedSeconds=time.monotonic() - started,
                  completedAt=datetime.now(timezone.utc).isoformat(),
                  classification="success" if code == 0 else classify(text))
    write_json(output / f"{name}.json", record)
    return code


def collect(product, output=EVIDENCE):
    """Copy available unsigned diagnostics only; absent producers do not cause secondary failures."""
    target = output / "diagnostics"
    patterns = ("**/build/test-results/**/*", "**/build/reports/**/*", "**/build/*parity*/**/*",
                "**/build/**/logs/*.log", "**/build/**/crash-reports/*.txt", "**/build/**/*evidence*/*",
                "**/build/**/hs_err_pid*.log", "**/build/**/trace*.zip", "**/build/release-verification/tasks.json")
    for pattern in patterns:
        for source in product.glob(pattern):
            if not source.is_file() or source.is_symlink() or any(p.is_symlink() for p in source.parents):
                continue
            destination = target / source.relative_to(product)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if source.suffix in (".log", ".txt", ".xml", ".html", ".json", ".properties", ".csv"):
                destination.write_text(redact(source.read_text(encoding="utf-8", errors="replace")), encoding="utf-8")
            else:
                shutil.copyfile(source, destination)


def receipt(product, output=EVIDENCE):
    """Seal a successful full-command receipt with actual task inventory and diagnostic hashes."""
    expected = identity(product)
    for name in ("native", "npm", "browsers", "check"):
        record = json.loads((output / f"{name}.json").read_text())
        require(all(record.get(k) == v for k, v in expected.items()) and record.get("exitCode") == 0,
                f"{name} did not succeed for this source and producer.")
    check = json.loads((output / "check.json").read_text())
    require(check["command"] == check_command(), "The complete release verification command differs.")
    tasks = [dict(task, root=path.parent.parent.parent.relative_to(product).as_posix())
             for path in sorted(product.glob("**/build/release-verification/tasks.json"))
             for task in json.loads(path.read_text())]
    require(tasks and len({(task["root"], task["build"], task["path"]) for task in tasks}) == len(tasks),
            "The executed verification graph is empty or duplicated.")
    require({":check", ":koverHtmlReport", ":koverXmlReport"} <= {task["path"] for task in tasks},
            "The complete check and coverage graph is missing.")
    files = {p.relative_to(output).as_posix(): digest(p) for p in sorted(output.rglob("*"))
             if p.is_file() and p != output / "receipt.json"}
    write_json(output / "receipt.json", dict(expected, command=FULL_CHECK, tasks=tasks, files=files))


def check_command():
    """Keep the original full check inventory; the observer only exports provenance."""
    return ["bash", "./gradlew", *FULL_CHECK, "--stacktrace", "--no-configuration-cache",
            "--init-script", "../release/verification-tasks.gradle"]


def summarize_phases(output=EVIDENCE):
    """Expose the first failed or interrupted producer without inventing absent screenshots."""
    for name in ("native", "npm", "browsers", "check"):
        path = output / f"{name}.json"
        if path.is_file():
            record = json.loads(path.read_text())
            if record.get("exitCode") != 0:
                return dict(phase=name, classification=record.get("classification", "interrupted phase; inspect diagnostics"))
    return dict(phase="", classification="success" if (output / "check.json").is_file()
                else "provisioning failed before verification; inspect job steps")


def validate_receipt(root, expected):
    """Reject missing, stale, altered or rehearsal verification at the signing boundary."""
    value = json.loads((root / "receipt.json").read_text())
    require(all(value.get(k) == v for k, v in expected.items()), "Verification source or producer differs.")
    require(value.get("command") == FULL_CHECK and value.get("tasks"), "Full verification inventory is missing.")
    files = {p.relative_to(root).as_posix(): digest(p) for p in sorted(root.rglob("*"))
             if p.is_file() and p != root / "receipt.json"}
    require(value.get("files") and value["files"] == files, "Verification artifact contents differ.")
    return value


def gate(product, results, output=Path("build/verification-gate.json")):
    """Require successful analysis and trusted, digest-bound full verification before signing."""
    require(results["source"]["result"] == "success" and results["analysis"]["result"] == "success",
            "Source admission and every required analysis must succeed before signing.")
    verification = results["verification"]
    require(verification["result"] == "success", "Full verification did not succeed.")
    outputs = verification["outputs"]
    expected = identity(product)
    analysis = results["analysis"]["outputs"]
    require(analysis.get("source_commit") == expected["sourceCommit"]
            and analysis.get("build_logic_source_commit") == expected["sourceCommit"]
            and analysis.get("controller_commit") == expected["controllerCommit"]
            and analysis.get("producer_run_id") == str(expected["runId"])
            and 0 < int(analysis["producer_attempt"]) <= expected["runAttempt"]
            and all(re.fullmatch(r"[0-9a-f]{64}", analysis.get(key, ""))
                    for key in ("product_report_digest", "build_logic_report_digest")),
            "Required analysis identities or report digests differ.")
    attempt = int(outputs["producer_attempt"])
    require(0 < attempt <= expected["runAttempt"], "Verification producer attempt is invalid.")
    expected["runAttempt"] = attempt
    artifact_id = outputs["artifact_id"]
    require(re.fullmatch(r"[1-9][0-9]*", artifact_id), "Verification artifact is missing.")
    repo, run = os.environ["GITHUB_REPOSITORY"], expected["runId"]
    if expected["scope"] == "production":
        pages = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp",
                                                   f"repos/{repo}/actions/runs/{run}/artifacts?per_page=100"]))
        require(not any(a["name"].startswith("prepared-") for page in pages for a in page["artifacts"]),
                "This run already saved signed preparation; reuse its prepared_run_id instead of signing again.")
    artifact = json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact_id}"]))
    binding = artifact.get("workflow_run", {})
    require(binding.get("id") == run and binding.get("head_sha") == expected["controllerCommit"]
            and artifact.get("digest") == "sha256:" + outputs["artifact_digest"] and not artifact.get("expired"),
            "Verification artifact producer or digest differs.")
    root = Path("build/verified-product")
    require(not root.exists(), "Verification download directory already exists.")
    require(artifact["name"] == f"{expected['scope']}-verification-{attempt}", "Verification artifact name differs.")
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory) / "verification.zip"
        with archive.open("wb") as stream:
            subprocess.run(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact_id}/zip"], stdout=stream, check=True)
        require(digest(archive) == outputs["artifact_digest"] and archive.stat().st_size == artifact["size_in_bytes"],
                "Downloaded verification artifact digest or size differs.")
        with ZipFile(archive) as files:
            require(len(files.namelist()) == len(set(files.namelist()))
                    and sum(info.file_size for info in files.infolist()) <= 4 * 1024**3,
                    "Verification artifact is duplicated or oversized.")
            for name in files.namelist():
                require(not name.startswith("/") and ".." not in Path(name).parts and "\\" not in name and ":" not in name,
                        "Unsafe verification artifact entry.")
            files.extractall(root)
    value = validate_receipt(root, expected)
    require(digest(root / "receipt.json") == outputs["receipt_digest"], "Verification receipt digest differs.")
    write_json(output, dict(expected, analysis="success", verificationArtifactId=int(artifact_id),
                            verificationArtifactDigest=artifact["digest"], receiptDigest=outputs["receipt_digest"],
                            analysisEvidence=analysis, taskCount=len(value["tasks"])))


def main():
    """Expose shared verification phases to production and non-publishing rehearsals."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("init", "profile", "native", "npm", "browsers", "check", "collect", "summary", "receipt", "gate"))
    parser.add_argument("--product", type=Path, default=Path("product"))
    args = parser.parse_args()
    if args.operation == "init":
        write_json(EVIDENCE / "source.json", identity(args.product))
    elif args.operation == "profile":
        write_json(EVIDENCE / "runtime-profile.json", runtime_profile(args.product))
    elif args.operation == "collect":
        collect(args.product)
    elif args.operation == "summary":
        value = summarize_phases()
        write_json(EVIDENCE / "summary.json", value)
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, entry in value.items():
                output.write(f"{key}={entry}\n")
    elif args.operation == "receipt":
        receipt(args.product)
    elif args.operation == "gate":
        gate(args.product, json.loads(os.environ["RESULTS"]))
    else:
        commands = {"native": ["bash", "-c", 'set -euo pipefail; apt_options=(-o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30); sudo apt-get "${apt_options[@]}" update; sudo apt-get "${apt_options[@]}" install --yes --no-install-recommends libegl1 libegl-mesa0'],
                    "npm": ["bash", "./gradlew", "kotlinNpmInstall", "--stacktrace"],
                    "browsers": ["node", "build/js/node_modules/playwright/cli.js", "install", "--with-deps", "chromium", "firefox", "webkit"],
                    "check": check_command()}
        sys.exit(phase(args.operation, args.product, commands[args.operation]))


if __name__ == "__main__":
    main()
