#!/usr/bin/env python3
"""Share real signed packaging and immutable assembly without sharing publication admission."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile

import verification

spec = importlib.util.spec_from_file_location("prepared", Path(__file__).with_name("prepared-release.py"))
prepared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepared)


def select_rehearsal(commit, controller):
    """Admit only immutable existing sources, retaining their ordinary build version."""
    verification.require(re.fullmatch(r"[0-9a-f]{40}", commit) and re.fullmatch(r"[0-9a-f]{40}", controller),
                         "Rehearsal requires full immutable product and controller SHAs.")
    verification.require(controller == os.environ["GITHUB_SHA"], "Selected rehearsal controller differs from this workflow.")
    subprocess.run(["git", "cat-file", "-e", commit + "^{commit}"], check=True)
    source = subprocess.check_output(["git", "show", commit + ":build.gradle.kts"], text=True)
    match = re.search(r'^version = "([0-9]+\.[0-9]+\.[0-9]+)"$', source, re.MULTILINE)
    verification.require(match is not None, "Rehearsal requires the product's existing stable build version.")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"tag=v{match.group(1)}\n")


def build(tag, commit, scope):
    """Build, sign and resolve consumers in the runner-owned empty Maven repository."""
    verification.require(scope in ("production", "rehearsal"), "Unknown preparation scope.")
    gate = json.loads(Path("build/verification-gate.json").read_text())
    expected = verification.identity(Path("product"))
    verification.require(gate["scope"] == scope and gate["sourceCommit"] == commit
                         and all(gate[k] == expected[k] for k in ("controllerCommit", "tagObject", "runId"))
                         and gate["analysis"] == "success", "Preparation lacks its verified quality gate.")
    project_id = json.loads(Path("product/release/modrinth-project.json").read_text())["projectId"]
    command = ["bash", "./gradlew", "--init-script", "../release/prepare-fabric-sources.gradle",
               "verifyPublishedConsumer", "modrinthReleaseManifest", "hangarReleaseManifest",
               f"-Pstrata.sourceRevision={tag if scope == 'production' else commit}",
               f"-Pstrata.sourceCommit={commit}", f"-Pstrata.modrinthProjectId={project_id}", "--stacktrace"]
    if scope == "rehearsal":
        notes = Path("build/rehearsal-notes.md").resolve()
        notes.write_text(f"# Non-publishing rehearsal\n\nProduct: {commit}\nController: {os.environ['GITHUB_SHA']}\n", encoding="utf-8")
        os.environ["STRATA_REHEARSAL_NOTES"] = str(notes)
        command += ["--init-script", "../release/rehearsal-metadata.gradle"]
    return verification.phase("signing", Path("product"), command, Path("build/preparation-diagnostics"))


def assemble(tag, commit, tag_object, scope, repository):
    """Package original signatures and bind the complete quality gate into the saved inventory."""
    current = verification.identity(Path("product"))
    verification.require(current["scope"] == scope and current["sourceCommit"] == commit
                         and current["tagObject"] == tag_object, "Signed preparation sources differ from admission.")
    rehearsal = scope == "rehearsal"
    name = "rehearsal-prepared" if rehearsal else "release-prepared"
    output = Path("build/rehearsal" if rehearsal else "build/prepared")
    prepared.prepare(Path("product"), repository, output, tag, commit, tag_object, scope=scope,
                     notes=Path("build/rehearsal-notes.md") if rehearsal else None,
                     verification=Path("build/verification-gate.json"))
    archive_path = Path(f"build/{name}.tar.gz")
    with tarfile.open(archive_path, "w:gz") as archive:
        for path in sorted(output.iterdir()):
            archive.add(path, arcname=path.name)
    archive_path.with_name(f"{name}.sha256").write_text(prepared.sha256(archive_path) + "\n", encoding="ascii")


def main():
    """Run shared packaging only after separate source admission and quality verification."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("select-rehearsal", "build", "assemble"))
    parser.add_argument("--tag")
    parser.add_argument("--commit", required=True)
    parser.add_argument("--controller")
    parser.add_argument("--scope", choices=("production", "rehearsal"))
    parser.add_argument("--tag-object", default="")
    parser.add_argument("--repository", type=Path)
    args = parser.parse_args()
    if args.operation == "select-rehearsal":
        select_rehearsal(args.commit, args.controller)
    elif args.operation == "build":
        sys.exit(build(args.tag, args.commit, args.scope))
    else:
        assemble(args.tag, args.commit, args.tag_object, args.scope, args.repository)


if __name__ == "__main__":
    main()
