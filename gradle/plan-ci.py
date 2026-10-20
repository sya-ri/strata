#!/usr/bin/env python3
"""Select verification jobs from changed paths and the existing Gradle source model."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess


def plan(paths, model=None, full=False):
    """Unknown inputs deliberately select the complete suite instead of silently skipping it."""
    result = {name: False for name in ("common", "web", "docs", "docs_full", "workflow", "qodana", "all_minecraft", "preflight")}
    if model is not None and (not isinstance(model, dict) or set(model) != {"minecraft", "documentation"}
                              or not isinstance(model["minecraft"], dict) or not isinstance(model["documentation"], list)):
        full, model = True, None
    native_sources = (model or {}).get("minecraft", {})
    documentation_sources = (model or {}).get("documentation", [])
    versions = set()
    for path in paths:
        if path.startswith(".github/performance/") or path == ".github/performance" or path == "gradle/performance/paired-jvm.example.json":
            result.update(workflow=True, preflight=True)
            continue
        result["preflight"] |= path.startswith("gradle/performance/") or path in ("gradle/tests/test_paired_jvm.py", ".github/workflows/jvm.yml")
        if path.startswith("build-logic/src/test/") or path == "gradle/qodana-idea-model.gradle.kts":
            result.update(common=True, workflow=True, qodana=True)
        elif path.startswith("gradle/tests/") or (path.startswith("gradle/") and path.endswith("-fixtures.sh")) or (path.startswith("gradle/performance/") and path.endswith(".py")):
            result["workflow"] = True
        elif path.startswith(("release/", ".github/workflows/publish", ".github/workflows/release", ".github/actions/use-prepared")):
            result["workflow"] = True
        elif path.startswith((".github/workflows/qodana", "qodana.yaml", "gradle/prepare-qodana", "gradle/verify-qodana")):
            result.update(workflow=True, qodana=True)
        elif path.startswith((".github/workflows/", ".github/actions/", "gradle/plan-", "gradle/verify-minecraft-ci")):
            result["workflow"] = True
            result["docs_full"] |= path.startswith(".github/workflows/pages")
        elif path.startswith(("runtime/minecraft-fabric-", "integration/minecraft-fabric-", "runtime/shared/", "integration/shared/")):
            consumers = {version for version, roots in native_sources.items()
                         if any(path == root or path.startswith(root.rstrip("/") + "/") for root in roots)}
            if consumers:
                versions.update(consumers)
            else:
                result["all_minecraft"] = True
            result["docs_full"] |= any(path == root or path.startswith(root.rstrip("/") + "/") for root in documentation_sources)
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


def client_plan(root, versions, backend, subject_ref):
    """Admit exact paired targets declaring the existing full backend client contract."""
    if not re.fullmatch(r"[0-9a-f]{40}", subject_ref):
        raise ValueError("subject_ref must be a full lowercase immutable commit SHA")
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if actual != subject_ref:
        raise ValueError("Subject checkout differs from subject_ref")
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root):
        raise ValueError("Subject checkout has modified tracked sources")
    if backend not in ("opengl", "vulkan"):
        raise ValueError("minecraft_backend must be opengl or vulkan")
    selected = versions.split(",")
    if any(not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", version) for version in selected) or len(set(selected)) != len(selected):
        raise ValueError("minecraft_versions must contain distinct exact numeric versions separated by commas")
    entries = []
    for version in sorted(selected, key=lambda value: tuple(map(int, value.split(".")))):
        projects = [f"{kind}/minecraft-fabric-{version}" for kind in ("runtime", "integration")]
        if not all((root / project / "build.gradle.kts").is_file() for project in projects):
            raise ValueError(f"Unknown paired Minecraft target: {version}")
        build = (root / projects[1] / "build.gradle.kts").read_text(encoding="utf-8")
        declaration = re.search(r"enum class Backend\(val argument: String\)\s*\{([^}]+)\}", build)
        tasks = [f":integration:minecraft-fabric-{version}:{name}" for name in ("runClientGameTest", "runProductionClientGameTest")]
        check = re.search(r'tasks\.named\("check"\)\s*\{\s*dependsOn\("runClientGameTest",\s*runProductionClientGameTest\)\s*\}', build)
        if (not declaration or f'("{backend}")' not in declaration[1]
                or 'Scope.Full' not in build or '"strata.canvas.backend"' not in build
                or not check
                or any(f'"{task.rsplit(":", 1)[1]}"' not in build for task in tasks)):
            raise ValueError(f"Minecraft {version} does not declare full {backend} development and production client verification")
        entries.append(dict(id=f"minecraft-{version.replace('.', '-')}-{backend}", version=version,
                            documentation=False, name=f"Minecraft {version} {backend} full clients",
                            gradle_arguments=" ".join([f":integration:minecraft-fabric-{version}:check", f"-Pstrata.minecraftVersions={version}",
                                                               f"-Pstrata.canvas.backend={backend}", "-Pstrata.canvas.scope=full", "--continue"]),
                            loom_projects="\n".join(projects)))
    result = plan([])
    result.update(minecraft=[entry["version"] for entry in entries], subject_ref=subject_ref,
                  client_backend=backend, client_matrix={"include": entries})
    return result


def check_results(jobs):
    """Require exactly the selected successes and planned skips, including opt-in jobs."""
    assert jobs["plan"]["result"] == "success", "CI planning failed"
    outputs = jobs["plan"]["outputs"]
    common, web, docs, full_docs, workflow = (outputs[key] == "true" for key in ("common", "web", "docs", "docs_full", "workflow"))
    minecraft = outputs["all_minecraft"] == "true" or json.loads(outputs["minecraft"]) != [] or full_docs
    expected = {"checks": common, "web-demos": web, "web": web, "workflow-checks": workflow or common,
                "minecraft-matrix": minecraft, "minecraft": minecraft, "documentation": full_docs, "docs": docs and not full_docs,
                "paired-jvm": outputs.get("paired_manifest", "") != "", "preflight-qualification": outputs.get("preflight", "false") == "true"}
    for job, selected in expected.items():
        required = "success" if selected else "skipped"
        assert jobs[job]["result"] == required, f"{job}: expected {required}, got {jobs[job]['result']}"


def main():
    """Write one machine-readable plan and matching GitHub job outputs."""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--base")
    mode.add_argument("--full", action="store_true")
    mode.add_argument("--paired-manifest", help="Validate an explicit manual measurement input instead of ordinary verification")
    mode.add_argument("--subject-ref")
    mode.add_argument("--check-results", action="store_true", help="Validate the completed jobs supplied in RESULTS")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--model", type=Path)
    parser.add_argument("--minecraft-versions", default="")
    parser.add_argument("--minecraft-backend", default="default")
    parser.add_argument("--subject-root", type=Path, default=Path("subject"))
    parser.add_argument("--output", type=Path, default=Path("build/github-actions/ci-plan.json"))
    args = parser.parse_args()
    if args.check_results:
        check_results(json.loads(os.environ["RESULTS"]))
        return
    selected_client = bool(args.minecraft_versions or args.subject_ref is not None or args.minecraft_backend != "default")
    if selected_client and (args.paired_manifest is not None or args.base is not None or args.full or args.head != "HEAD" or args.model is not None):
        parser.error("--subject-ref requires an isolated loaded-client request without PR, paired or model arguments")
    paths = []
    if args.paired_manifest is not None:
        if os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or not args.paired_manifest or args.head != "HEAD" or args.model is not None:
            parser.error("--paired-manifest requires an explicit nonempty workflow_dispatch input without PR or model arguments")
        specification = importlib.util.spec_from_file_location("paired_jvm", Path(__file__).with_name("performance") / "paired-jvm.py")
        controller = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(controller)
        controller.select_manifest(Path.cwd(), explicit=args.paired_manifest)
    elif not args.full and not selected_client:
        # No rename detection: both the old and new owners must be checked.
        paths = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", "-z", args.base + "..." + args.head]).decode().strip("\0").split("\0")
    model = json.loads(args.model.read_text()) if args.model else None
    try:
        result = (client_plan(args.subject_root, args.minecraft_versions, args.minecraft_backend, args.subject_ref)
                  if selected_client else plan([p for p in paths if p], model, args.full))
    except ValueError as error:
        parser.error(str(error))
    if args.paired_manifest is not None:
        result["preflight"] = True
    result["source_model"] = args.base is not None and args.model is None and result["all_minecraft"] and not result["common"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for name, value in result.items():
                output.write(name + "=" + (value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
