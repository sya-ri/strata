#!/usr/bin/env python3
"""Select verification jobs from changed paths and the existing Gradle source model."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def plan(paths, model=None, full=False):
    """Unknown inputs deliberately select the complete suite instead of silently skipping it."""
    result = {name: False for name in ("common", "web", "docs", "docs_full", "workflow", "qodana", "all_minecraft")}
    versions = set()
    for path in paths:
        if path.startswith("gradle/tests/") or (path.startswith("gradle/") and path.endswith("-fixtures.sh")):
            result["workflow"] = True
        elif path.startswith(("release/", ".github/workflows/publish", ".github/workflows/release", ".github/actions/use-prepared")):
            result["workflow"] = True
        elif path.startswith((".github/workflows/qodana", "qodana.yaml", "gradle/prepare-qodana", "gradle/verify-qodana")):
            result.update(workflow=True, qodana=True)
        elif path.startswith((".github/workflows/", ".github/actions/", "gradle/plan-", "gradle/verify-minecraft-ci")):
            result["workflow"] = True
            result["docs_full"] |= path.startswith(".github/workflows/pages")
        elif path.startswith(("runtime/minecraft-fabric-", "integration/minecraft-fabric-", "runtime/shared/", "integration/shared/")):
            consumers = {version for version, roots in (model or {}).items()
                         if any(path == root or path.startswith(root.rstrip("/") + "/") for root in roots)}
            if consumers:
                versions.update(consumers)
            else:
                result["all_minecraft"] = True
            result["qodana"] = True
        elif path.startswith(("runtime/web/", "integration/web/", "examples/web/", "tools/web/", "kotlin-js-store/")):
            result["web"] = True
            result["qodana"] |= path.endswith((".kt", ".kts"))
        elif path.startswith(("integration/docs/", "docs/images/", "docs/publication/dokka-")) or (path.startswith("docs/") and path.endswith((".png", ".gif", ".svg"))):
            result["docs_full"] = True
            result["qodana"] |= path.endswith((".kt", ".kts"))
        elif path.startswith(("docs/", "skills/")) or path in ("README.md", "CHANGELOG.md"):
            result["docs"] = True
        elif path.startswith(("api/", "runtime/", "integration/", "examples/", "paper-api/", "velocity-api/", "quality/", "detekt-rules/", "performance-testkit/")):
            result.update(common=True, web=True, all_minecraft=True, qodana=True, docs_full=True)
        elif path.startswith("build-logic/") or path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties") or path.startswith(("gradle/", "config/")):
            full = True
        elif path in ("AGENTS.md", ".gitignore", ".gitattributes", "LICENSE"):
            continue
        else:
            full = True
    if full:
        result = dict.fromkeys(result, True)
    result["minecraft"] = sorted(versions, key=lambda version: tuple(map(int, version.split("."))))
    return result


def main():
    """Write one machine-readable plan and matching GitHub job outputs."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--full", action="store_true")
    parser.add_argument("--model", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/github-actions/ci-plan.json"))
    args = parser.parse_args()
    paths = []
    if not args.full:
        if not args.base:
            parser.error("--base or --full is required")
        # No rename detection: both the old and new owners must be checked.
        paths = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", "-z", args.base + "..." + args.head]).decode().strip("\0").split("\0")
    model = json.loads(args.model.read_text()) if args.model else None
    result = plan([p for p in paths if p], model, args.full)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for name, value in result.items():
                output.write(name + "=" + json.dumps(value, separators=(",", ":")) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
