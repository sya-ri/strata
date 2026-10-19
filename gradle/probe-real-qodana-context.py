"""Inspect one complete native dependency context without claiming whole-repository acceptance."""

import collections
import hashlib
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


def checker():
    """Reuse the production report and URL parsers for the diagnostic only."""
    spec = importlib.util.spec_from_file_location("declarations", root / "gradle/verify-qodana-declarations.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def compiler_sources(source):
    """Project exactly the compiler-selected files without changing authored source membership."""
    prefix = source["path"] + "/"
    inputs = source["inputs"]
    assert len(set(inputs)) == len(inputs)
    assert all(path.startswith(prefix) and path in source["files"] for path in inputs)
    return {path[len(prefix):]: source["files"][path] for path in inputs}


def copy_shared(owners, revision):
    """Test distinct physical compiler inputs while retaining exact original selected bytes."""
    parser = checker()
    contexts = collections.defaultdict(set)
    for name, owner in owners.items():
        for source in owner["roots"]:
            if source["exists"] and not source["generated"] and source["kind"] in {"Source", "TestSource"}:
                contexts[source["path"]].add(name)
    copies = []
    for name, owner in owners.items():
        document = ET.parse(root / owner["iml"])
        manager = document.getroot().find("./component[@name='NewModuleRootManager']")
        directory = root / owner["directory"] if owner["directory"] else root
        for content in list(manager.findall("content")):
            for folder in list(content.findall("sourceFolder")):
                original = parser.url_path(root, directory, folder.attrib["url"])
                relative = original.relative_to(root).as_posix()
                if parser.iml_kind(folder) not in {"Source", "TestSource"} or len(contexts[relative]) < 2:
                    continue
                current = {path.relative_to(original).as_posix(): parser.sha256(path) for path in original.rglob("*") if path.is_file()}
                declaration = next(source for source in owner["roots"] if source["path"] == relative)
                assert declaration["files"] == {relative + "/" + key: value for key, value in current.items()}
                selected = compiler_sources(declaration)
                destination = root / "qodana-analysis-sources" / revision / name / hashlib.sha256(relative.encode()).hexdigest()[:16]
                destination.mkdir(parents=True, exist_ok=True)
                for path in selected:
                    target = destination / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(original / path, target)
                copied = {path.relative_to(destination).as_posix(): parser.sha256(path) for path in destination.rglob("*") if path.is_file()}
                assert copied == selected
                attributes = dict(folder.attrib, url="file://" + destination.as_posix())
                ET.SubElement(ET.SubElement(manager, "content", url=attributes["url"]), "sourceFolder", **attributes)
                content.remove(folder)
                copies.append(dict(module=name, original=relative, copy=destination.relative_to(root).as_posix(), kind=parser.iml_kind(folder), files=selected, inputs=declaration["inputs"]))
            if not content.findall("sourceFolder") and content.attrib["url"] != "file://$MODULE_DIR$/":
                manager.remove(content)
        document.write(root / owner["iml"], encoding="utf-8", xml_declaration=True)
    (evidence / "probe-copies.json").write_text(json.dumps(copies, indent=2), encoding="utf-8")
    assert copies


def bind_compiler_sdks(owners, revision):
    """Bind each JVM module to its resolved compiler installation without inferring an SDK from bytecode targets."""
    metadata = json.loads((evidence / "probe-sdks.json").read_text(encoding="utf-8"))
    assert metadata["revision"] == revision
    application = ET.Element("application")
    table = ET.SubElement(application, "component", name="ProjectJdkTable")
    sdks = {}
    bindings = {}
    for owner in metadata["projects"]:
        name = owner["module"]
        if name not in owners:
            continue
        home = pathlib.Path(owner["installation"]).resolve(strict=True)
        identity = (home.as_posix(), owner["java"], owner["runtimeVersion"], owner["vendor"])
        sdk_name = "compiler-" + str(owner["java"]) + "-" + hashlib.sha256(json.dumps(identity).encode()).hexdigest()[:12]
        if sdk_name not in sdks:
            java = home / "bin/java"
            if not java.is_file():
                java = home / "bin/java.exe"
            modules = sorted(line.split("@", 1)[0] for line in subprocess.check_output([str(java), "--list-modules"], text=True, stderr=subprocess.PIPE).splitlines())
            assert "java.base" in modules and len(set(modules)) == len(modules)
            class_roots = [f"jrt://{home.as_posix()}!/{module}" for module in modules]
            sdk = ET.SubElement(table, "jdk", version="2")
            for tag, value in (("name", sdk_name), ("type", "JavaSDK"), ("version", f'java version "{owner["runtimeVersion"]}"'), ("homePath", home.as_posix())):
                ET.SubElement(sdk, tag, value=value)
            roots = ET.SubElement(sdk, "roots")
            classes = ET.SubElement(ET.SubElement(roots, "classPath"), "root", type="composite")
            for url in class_roots:
                ET.SubElement(classes, "root", type="simple", url=url)
            sources = ET.SubElement(ET.SubElement(roots, "sourcePath"), "root", type="composite")
            if (home / "lib/src.zip").is_file():
                ET.SubElement(sources, "root", type="simple", url=f"jar://{home.as_posix()}/lib/src.zip!/")
            ET.SubElement(ET.SubElement(roots, "javadocPath"), "root", type="composite")
            ET.SubElement(sdk, "additional")
            sdks[sdk_name] = dict(java=owner["java"], installation=home.as_posix(), runtimeVersion=owner["runtimeVersion"], roots=class_roots)
        document = ET.parse(root / owners[name]["iml"])
        manager = document.getroot().find("./component[@name='NewModuleRootManager']")
        previous = [item for item in manager.findall("orderEntry") if item.attrib.get("type") in {"jdk", "inheritedJdk"}]
        assert len(previous) == 1, name
        manager.remove(previous[0])
        ET.SubElement(manager, "orderEntry", type="jdk", jdkName=sdk_name, jdkType="JavaSDK")
        document.write(root / owners[name]["iml"], encoding="utf-8", xml_declaration=True)
        assert name not in bindings
        bindings[name] = sdk_name
    assert all(name in bindings for name, owner in owners.items() if owner.get("compiler")), "Every actual JVM Kotlin owner needs its compiler SDK."
    configuration = evidence / "probe-ide-config/options/jdk.table.xml"
    configuration.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(application).write(configuration, encoding="utf-8", xml_declaration=True)
    (evidence / "probe-sdk-bindings.json").write_text(json.dumps(dict(revision=revision, sdks=sdks, modules=bindings), indent=2), encoding="utf-8")
    print("Bound", len(bindings), "actual JVM modules to", len(sdks), "resolved compiler SDKs.")


def configure():
    """Reuse the normal profile and full compilation bootstrap, changing only the opened module set."""
    text = (root / "qodana.yaml").read_text(encoding="utf-8")
    bootstrap = next(line for line in text.splitlines() if line.startswith("bootstrap:"))
    sdk_export = " && bash ./gradlew :probeQodanaCompilerSdks -Pstrata.completeIdeaModel=true --no-daemon --console=plain" if os.environ["STRATA_PROBE_CONTEXT"] == "copy-shared-sdk" else ""
    text = text.replace(bootstrap, bootstrap + sdk_export + " && python3 gradle/probe-real-qodana-context.py select", 1)
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
    if context in {"copy-shared", "copy-shared-sdk"}:
        copy_shared(owners, revision)
        if context == "copy-shared-sdk":
            bind_compiler_sdks(owners, revision)
        (evidence / "probe-context.json").write_text(json.dumps(dict(context=context, revision=revision, modules=sorted(owners)), indent=2), encoding="utf-8")
        return
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
    parser = checker()
    parser.verify_report(report, expected["revision"])
    model = json.loads((results / "projectStructure/Modules.json").read_text(encoding="utf-8"))
    assert collections.Counter(module["name"] for module in model["modules"]) == collections.Counter(expected["modules"])
    inventory = parser.read_json(evidence / "declarations.json")
    inventory["projects"] = [owner for owner in inventory["projects"] if owner["module"] in expected["modules"]]
    if expected["context"] in {"copy-shared", "copy-shared-sdk"}:
        copies = parser.read_json(evidence / "probe-copies.json")
        replacements = {(item["module"], item["kind"], item["original"]): item for item in copies}
        imported = {module["name"]: module for module in model["modules"]}
        for owner in inventory["projects"]:
            sources = []
            for source in owner["roots"]:
                if not source["exists"] or source["generated"]:
                    continue
                item = replacements.get((owner["module"], source["kind"], source["path"]))
                path = root / (item["copy"] if item else source["path"])
                actual = {file.relative_to(path).as_posix(): parser.sha256(file) for file in path.rglob("*") if file.is_file()}
                if item:
                    assert actual == item["files"] == compiler_sources(source)
                    assert item["inputs"] == source["inputs"]
                    original = root / source["path"]
                    assert {file.relative_to(root).as_posix(): parser.sha256(file) for file in original.rglob("*") if file.is_file()} == source["files"]
                else:
                    assert {source["path"] + "/" + key: value for key, value in actual.items()} == source["files"]
                sources.append((source["kind"], path))
            actual = [(folder["type"], parser.url_path(root, root, folder["path"])) for content in imported[owner["module"]].get("contentEntries", []) for folder in content.get("sourceFolders", [])]
            assert collections.Counter(actual) == collections.Counter(sources), owner["module"]
    else:
        parser.verify_source_ownership(root, inventory, model)
    if expected["context"] == "copy-shared-sdk":
        sdk_expectations = parser.read_json(evidence / "probe-sdk-bindings.json")
        assert sdk_expectations["revision"] == expected["revision"]
        sdks = parser.read_json(results / "projectStructure/SDKs.json")["sdks"]
        for name, sdk in sdk_expectations["sdks"].items():
            imported = [entry for entry in sdks if entry["name"] == name]
            assert len(imported) == 1 and imported[0]["sdkType"] == "JavaSDK"
            assert imported[0]["sdkVersion"] == f'java version "{sdk["runtimeVersion"]}"'
            assert collections.Counter(imported[0]["roots"]) == collections.Counter(sdk["roots"]), name
        imported = {module["name"]: module for module in model["modules"]}
        for name, sdk in sdk_expectations["modules"].items():
            assert [entry["name"] for entry in imported[name]["orderEntries"] if entry["type"] == "SDK"] == [sdk], name
    parser.verify_analysis(root, model, results)
    findings = [item for run in report["runs"] for item in run.get("results", [])]
    print(json.dumps(dict(context=expected["context"], modules=expected["modules"], findings=[(item["ruleId"], item["message"]["text"]) for item in findings]), indent=2))
    assert not findings, "The actual source context must pass the unchanged zero-problem gate."


if sys.argv[1] == "configure":
    configure()
elif sys.argv[1] == "select":
    select()
else:
    inspect(pathlib.Path(sys.argv[2]))
