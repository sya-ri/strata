"""Check real compiled IDEA modules using a synthetic imported-model fixture, without claiming analysis results."""

import copy
import importlib.util
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("declarations", root / "gradle/verify-qodana-declarations.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
inventory = checker.read_json(root / "build/qodana/declarations.json")
project_file, = root.glob("*.ipr")
project = ET.parse(project_file).getroot()
idea = root / ".idea"
idea.mkdir(exist_ok=True)
modules_project = ET.Element("project")
modules_project.append(copy.deepcopy(project.find("./component[@name='ProjectModuleManager']")))
(idea / "modules.xml").write_text(ET.tostring(modules_project, encoding="unicode"), encoding="utf-8")
sdk = copy.deepcopy(project.find("./component[@name='ProjectRootManager']"))
sdk_name = sdk.attrib.setdefault("project-jdk-name", "fixture-jdk")
project_java = int(sdk.attrib["languageLevel"][4:])
misc = ET.Element("project")
misc.append(sdk)
(idea / "misc.xml").write_text(ET.tostring(misc, encoding="unicode"), encoding="utf-8")
modules = []
java = []
for owner in inventory["projects"]:
    if not owner["iml"]:
        continue
    directory = root / owner["directory"] if owner["directory"] else root
    document = ET.parse(root / owner["iml"]).getroot()
    manager = document.find("./component[@name='NewModuleRootManager']")
    contents = []
    for content in manager.findall("content"):
        contents.append(dict(path=content.attrib["url"].replace("$MODULE_DIR$", directory.as_posix()),
                             sourceFolders=[dict(type=checker.iml_kind(folder), path=folder.attrib["url"].replace("$MODULE_DIR$", directory.as_posix())) for folder in content.findall("sourceFolder")],
                             excludeFolders=[dict(path=folder.attrib["url"].replace("$MODULE_DIR$", directory.as_posix())) for folder in content.findall("excludeFolder")]))
    entries = []
    for entry in manager.findall("orderEntry"):
        if entry.attrib["type"] in {"inheritedJdk", "jdk"}:
            entries.append(dict(type="SDK", name=entry.attrib.get("jdkName", sdk_name)))
        elif entry.attrib["type"] == "module":
            entries.append(dict(type="Module", name=entry.attrib["module-name"]))
        elif entry.attrib["type"] == "module-library":
            for classes in entry.findall("./library/CLASSES/root"):
                url = classes.attrib["url"]
                name = url[len("file://"):] if url.startswith("file://$PROJECT_DIR$/") else checker.url_path(root, directory, url, source=False).as_posix()
                entries.append(dict(type="Library", name=name))
    modules.append(dict(name=owner["module"], contentEntries=contents, orderEntries=entries))
    java.append(dict(name=owner["module"], languageLevel=int(manager.attrib.get("LANGUAGE_LEVEL", f"JDK_{project_java}")[4:])))
fixture = root / "build/qodana-import-fixture"
fixture.mkdir(exist_ok=True)
model_path = fixture / "Modules.json"
java_path = fixture / "Java.json"
model_path.write_text(json.dumps(dict(modules=modules)), encoding="utf-8")
java_path.write_text(json.dumps(dict(modules=java)), encoding="utf-8")
(fixture / "qodana.sarif.json").write_text(json.dumps(dict(runs=[dict(versionControlProvenance=[dict(revisionId=inventory["revision"])], invocations=[dict(executionSuccessful=True)])])), encoding="utf-8")
checker.verify_model(root, model_path, "", java_path)
print("Verified real compiler inputs, source ownership, output bytes, Kotlin identities and dependency closures using a synthetic import fixture.")
