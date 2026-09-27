#!/usr/bin/env python3
"""Collect the latest receipt per destination, including successful jobs from earlier attempts."""
import json
import os
from pathlib import Path
import re
import subprocess


def select(artifacts):
    """A failed-jobs rerun replaces only its own destination's evidence."""
    latest = {}
    for artifact in artifacts:
        match = re.fullmatch(r"result-(central|github|modrinth|curseforge|hangar)-([1-9][0-9]*)", artifact["name"])
        if match and not artifact.get("expired"):
            destination, attempt = match.group(1), int(match.group(2))
            if destination not in latest or latest[destination][0] < attempt:
                latest[destination] = (attempt, artifact["name"])
    return [name for _, name in latest.values()]


def main():
    """Read this run only; collection does not retry or publish a destination."""
    repository, run = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_RUN_ID"]
    pages = json.loads(subprocess.check_output(["gh", "api", "--paginate", "--slurp", f"repos/{repository}/actions/runs/{run}/artifacts?per_page=100"]))
    root = Path("build/release")
    root.mkdir(parents=True, exist_ok=True)
    for name in select([artifact for page in pages for artifact in page["artifacts"]]):
        subprocess.run(["gh", "run", "download", run, "--name", name, "--dir", str(root)], check=True)


if __name__ == "__main__":
    main()
