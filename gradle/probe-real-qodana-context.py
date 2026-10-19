"""Inspect one complete native dependency context without claiming whole-repository acceptance."""

import collections
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


root = pathlib.Path(__file__).resolve().parent.parent
evidence = root / "build/qodana"


def configure():
    """Reuse the normal profile and full compilation bootstrap, changing only the opened module set."""
    text = (root / "qodana.yaml").read_text(encoding="utf-8")
    bootstrap = next(line for line in text.splitlines() if line.startswith("bootstrap:"))
    text = text.replace(bootstrap, bootstrap + " && python3 gradle/probe-real-qodana-context.py select", 1)
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / "probe-context.yaml").write_text(text, encoding="utf-8")


def select():
    """Open both actual native owners and their complete original IDEA module dependency closure."""
    inventory = json.loads((evidence / "declarations.json").read_text(encoding="utf-8"))
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    assert inventory["revision"] == revision
    assert not subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=root)
    owners = {owner["module"]: owner for owner in inventory["projects"] if owner.get("iml")}
    context = os.environ["STRATA_PROBE_CONTEXT"]
    seeds = {name for name, owner in owners.items() if owner["native"] and owner["project"].rsplit(":", 1)[-1] == context}
    assert seeds, "The requested context must be declared by the actual configured project."
    selected = set(seeds)
    pending = list(seeds)
    while pending:
        name = pending.pop()
        model = ET.parse(root / owners[name]["iml"]).getroot()
        for dependency in model.findall("./component[@name='NewModuleRootManager']/orderEntry[@type='module']"):
            target = dependency.attrib["module-name"]
            assert target in owners, (name, target)
            if target not in selected:
                selected.add(target)
                pending.append(target)
    assert {name for name in selected if owners[name]["native"]} == seeds, "Another native context must not enter this analysis."
    descriptor = root / ".idea/modules.xml"
    shutil.copy2(descriptor, evidence / "probe-complete-modules.xml")
    project = ET.parse(descriptor)
    modules = project.getroot().find("./component[@name='ProjectModuleManager']/modules")
    by_path = {owner["iml"]: name for name, owner in owners.items()}
    actual = []
    for module in list(modules):
        url = module.attrib["fileurl"]
        prefix = "file://$PROJECT_DIR$/"
        assert url.startswith(prefix), url
        name = by_path[url[len(prefix):]]
        if name in selected:
            actual.append(name)
        else:
            modules.remove(module)
    assert collections.Counter(actual) == collections.Counter(selected)
    project.write(descriptor, encoding="utf-8", xml_declaration=True)
    (evidence / "probe-context.json").write_text(json.dumps(dict(context=context, revision=revision, modules=sorted(selected), seeds=sorted(seeds)), indent=2), encoding="utf-8")
    print("Opened the original compiler roots and dependencies for", context, sorted(selected))


def inspect(results):
    """Require actual, complete and zero-problem analysis of precisely the selected compiler context."""
    expected = json.loads((evidence / "probe-context.json").read_text(encoding="utf-8"))
    report = json.loads((results / "qodana.sarif.json").read_text(encoding="utf-8"))
    spec = importlib.util.spec_from_file_location("declarations", root / "gradle/verify-qodana-declarations.py")
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    checker.verify_report(report, expected["revision"])
    model = json.loads((results / "projectStructure/Modules.json").read_text(encoding="utf-8"))
    assert collections.Counter(module["name"] for module in model["modules"]) == collections.Counter(expected["modules"])
    inventory = checker.read_json(evidence / "declarations.json")
    inventory["projects"] = [owner for owner in inventory["projects"] if owner["module"] in expected["modules"]]
    checker.verify_source_ownership(root, inventory, model)
    checker.verify_analysis(root, model, results)
    findings = [item for run in report["runs"] for item in run.get("results", [])]
    print(json.dumps(dict(context=expected["context"], modules=expected["modules"], findings=[(item["ruleId"], item["message"]["text"]) for item in findings]), indent=2))
    assert not findings, "The actual source context must pass the unchanged zero-problem gate."


if sys.argv[1] == "configure":
    configure()
elif sys.argv[1] == "select":
    select()
else:
    inspect(pathlib.Path(sys.argv[2]))
