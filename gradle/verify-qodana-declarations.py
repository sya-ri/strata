#!/usr/bin/env python3
"""Compare fresh Gradle declarations with generated IDEA and imported Qodana ownership."""

import collections
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def fail(message):
    """Reject incomplete evidence with the declaration boundary in the diagnostic."""
    raise ValueError(f"Qodana declaration completeness: {message}")


def unique_keys(pairs):
    """Keep duplicate JSON keys from replacing declaration evidence."""
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key!r}")
        result[key] = value
    return result


def read_json(path):
    """Read strict UTF-8 JSON from a regular file without accepting a symlink."""
    if path.is_symlink() or not path.is_file():
        fail(f"expected regular non-symlink evidence: {path}")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys,
                      parse_constant=lambda value: fail(f"non-JSON constant: {value}"))


def sha256(path):
    """Hash current bytes for source and dependency identity."""
    if path.is_dir():
        files = {file.relative_to(path).as_posix(): sha256(file) for file in sorted(path.rglob("*")) if file.is_file()}
        data = json.dumps(files, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    else:
        data = path.read_bytes()
    return hashlib.sha256(data).hexdigest()


def repository_path(root, value):
    """Require exact canonical repository paths in the independent declaration inventory."""
    if not isinstance(value, str) or not value or any(ord(char) < 32 for char in value):
        fail(f"invalid declaration path: {value!r}")
    path = pathlib.PurePosixPath(value)
    if path.is_absolute() or "\\" in value or "%" in value or str(path) != value or ".." in path.parts:
        fail(f"non-canonical declaration path: {value!r}")
    resolved = root.joinpath(*path.parts).resolve()
    if resolved != root.joinpath(*path.parts) or root not in resolved.parents:
        fail(f"declaration path escapes or aliases repository ownership: {value!r}")
    return resolved


def url_path(root, module_dir, value, container_root="", source=True):
    """Decode IDE URLs at the adapter boundary and reject aliases before ownership comparison."""
    if not isinstance(value, str) or "\\" in value or "%" in value or any(ord(char) < 32 for char in value):
        fail(f"invalid model URL: {value!r}")
    value = value[:-2] if value.endswith("!/") else value
    prefixes = {scheme + variable: parent for scheme in ("file://", "jar://") for variable, parent in (("$PROJECT_DIR$/", root), ("$MODULE_DIR$/", module_dir))}
    for prefix, parent in prefixes.items():
        if value.startswith(prefix):
            relative = value[len(prefix):]
            if not source and relative.endswith("/"):
                relative = relative[:-1]
            if not relative:
                return parent
            parts = pathlib.PurePosixPath(relative)
            # MODULE_DIR links may contain ..; repository URLs may not.
            if parts.is_absolute() or str(parts) != relative or (parent == root and ".." in parts.parts):
                fail(f"non-canonical model URL: {value}")
            result = parent.joinpath(*parts.parts).resolve()
            break
    else:
        absolute = value[7:] if value.startswith("file://") else value[6:] if value.startswith("jar://") else value
        if os.name == "nt" and re.match(r"^/[A-Za-z]:/", absolute):
            absolute = absolute[1:]
        if container_root and absolute.startswith(container_root + "/"):
            relative = absolute[len(container_root) + 1:]
            result = repository_path(root, relative)
        else:
            result = pathlib.Path(absolute)
            if not result.is_absolute() or result.resolve() != result:
                fail(f"unsupported or non-canonical absolute model URL: {value}")
    if source and (root not in result.parents or not result.is_dir()):
        fail(f"model source escapes the repository or does not exist: {value}")
    return result


def read_iml(path):
    """Read only the exact module/root-manager/content source chain and direct dependency entries."""
    if path.is_symlink() or not path.is_file():
        fail(f"missing generated owner: {path}")
    text = path.read_text(encoding="utf-8-sig")
    if not text.startswith('<?xml version="1.0" encoding="UTF-8"?>') or "<!DOCTYPE" in text:
        fail(f"invalid generated module declaration: {path}")
    module = ET.fromstring(text)
    managers = module.findall("./component[@name='NewModuleRootManager']")
    if module.tag != "module" or len(managers) != 1:
        fail(f"invalid generated owner root manager: {path}")
    manager = managers[0]
    if len(manager.findall("content")) != 1:
        fail(f"invalid generated owner content: {path}")
    sources = manager.findall("./content/sourceFolder")
    if len(module.findall(".//sourceFolder")) != len(sources):
        fail(f"generated sources outside their content owner: {path}")
    return manager, sources


def iml_kind(folder):
    """Map external IDEA source/resource tags to Qodana's four canonical root kinds."""
    attributes = folder.attrib
    if set(attributes) == {"url", "isTestSource"}:
        return {"true": "TestSource", "false": "Source"}[attributes["isTestSource"]]
    if set(attributes) == {"url", "type"}:
        return {"java-resource": "Resource", "java-test-resource": "TestResource"}[attributes["type"]]
    fail(f"invalid generated source kind: {attributes}")


def verify(root, model_path, container_root, java_path):
    """Verify both downstream models against declaration and resolution data from this exact source."""
    inventory = read_json(root / "build/qodana/declarations.json")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=root):
        fail("acceptance requires clean tracked source bytes at the selected revision")
    if inventory["revision"] != revision:
        fail("inventory belongs to another revision")
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
    current = {name: sha256(repository_path(root, name)) for name in tracked if name}
    if inventory["tracked"] != current:
        fail("inventory source bytes or tracked membership changed")
    model = read_json(model_path)
    java = read_json(java_path)
    report_directory = java_path.parent.parent if java_path.parent.name == "projectStructure" else java_path.parent
    sarif = read_json(report_directory / "qodana.sarif.json")
    revisions = {entry.get("revisionId") for run in sarif["runs"] for entry in run.get("versionControlProvenance", [])}
    if revisions != {revision}:
        fail("the analysis report does not belong to the selected declaration revision")
    modules = collections.defaultdict(list)
    for module in model["modules"]:
        modules[module["name"]].append(module)
    owners = inventory["owners"]
    if not isinstance(owners, list) or not owners:
        fail("missing declared JMH owners")
    names = [owner["module"] for owner in owners]
    projects = [owner["project"] for owner in owners]
    declared_projects = inventory["projects"]
    declarations_by_project = {entry["project"]: entry for entry in declared_projects}
    if len(declarations_by_project) != len(declared_projects) or set(projects) != {entry["project"] for entry in declared_projects if entry["jmh"]}:
        fail("the complete configured project inventory and JMH owner inventory differ")
    if any(declarations_by_project[owner["project"]]["module"] != owner["module"] for owner in owners):
        fail("JMH owner identity differs from the complete configured project model")
    if len(set(names)) != len(names) or len(set(projects)) != len(projects):
        fail("duplicate declared owner identity")
    declarations_by_module = {entry["module"]: entry for entry in declared_projects if entry["module"]}
    if len(declarations_by_module) != sum(bool(entry["module"]) for entry in declared_projects):
        fail("duplicate configured module identity")

    def declared_shared_root(module_name, contents, kind, path):
        """Permit exact roots shared by actual configured declarations, including native showcase inputs."""
        declaration = declarations_by_module.get(module_name)
        if declaration is None:
            directories = {url_path(root, root, content["path"], source=False) for content in contents}
            candidates = [entry for entry in declared_projects if entry.get("directory") and repository_path(root, entry["directory"]) in directories]
            if len(candidates) == 1:
                declaration = candidates[0]
        return declaration is not None and any(item["kind"] == kind and repository_path(root, item["path"]) == path for item in declaration["roots"])

    project_model = root / ".idea/modules.xml"
    if project_model.is_symlink():
        fail("linked IDEA project metadata")
    module_entries = ET.parse(project_model).getroot().findall("./component[@name='ProjectModuleManager']/modules/module")
    sdk_model = ET.parse(root / ".idea/misc.xml").getroot().findall("./component[@name='ProjectRootManager']")
    if len(sdk_model) != 1:
        fail("missing project SDK")
    sdk = sdk_model[0].attrib["project-jdk-name"]
    project_java = int(sdk_model[0].attrib["languageLevel"][4:])
    for owner in owners:
        name = owner["module"]
        if len(modules[name]) != 1:
            fail(f"missing or duplicate imported JMH owner: {name}")
        directory = repository_path(root, owner["directory"])
        iml = repository_path(root, owner["iml"])
        if iml.stem != name:
            fail(f"generated module identity differs from declarations: {name}")
        entries = [entry for entry in module_entries if url_path(root, directory, entry.attrib["fileurl"], container_root, False) == iml]
        if len(entries) != 1:
            fail(f"generated project does not own exactly one JMH module: {name}")
        manager, folders = read_iml(iml)
        java_owners = [module for module in java["modules"] if module["name"] == name]
        if manager.attrib.get("LANGUAGE_LEVEL") != f"JDK_{owner['java']}" or len(java_owners) != 1 or java_owners[0].get("languageLevel") != owner["java"]:
            fail(f"wrong generated or imported owner language level: {name}")
        if url_path(root, directory, manager.find("content").attrib["url"], container_root, False) != directory:
            fail(f"wrong generated content owner: {name}")
        expected = []
        declarations = owner["roots"]
        if not any(item["sourceSet"] == "jmh" and item["kind"] == "TestSource" and item["exists"] and any(path.endswith((".kt", ".java")) for path in item["files"]) for item in declarations):
            fail(f"JMH owner has no declared working compiler inputs: {name}")
        if len({(item["sourceSet"], item["kind"], item["path"]) for item in declarations}) != len(declarations):
            fail(f"duplicate root declaration: {name}")
        for declaration in declarations:
            path = repository_path(root, declaration["path"])
            if declaration["exists"] != path.is_dir():
                fail(f"declared root existence changed: {name}: {path}")
            files = {file.relative_to(root).as_posix(): sha256(file) for file in path.rglob("*") if file.is_file()} if path.is_dir() else {}
            if files != declaration["files"]:
                fail(f"declared root membership or bytes changed: {name}: {path}")
            if not set(files).issubset(current):
                fail(f"authored root contains files outside the selected revision: {name}: {path}")
            if declaration["generated"]:
                fail(f"declared JMH owner hides roots under an excluded build directory: {name}: {path}")
            if declaration["exists"]:
                expected.append((declaration["kind"], path))
        expected = collections.Counter(expected)
        generated = collections.Counter((iml_kind(folder), url_path(root, directory, folder.attrib["url"], container_root)) for folder in folders)
        imported = modules[name][0]
        imported_content = imported.get("contentEntries", [])
        if len(imported_content) != 1 or url_path(root, directory, imported_content[0]["path"], source=False) != directory:
            fail(f"wrong imported content owner: {name}")
        if imported_content[0].get("excludePatterns", []):
            fail(f"imported owner patterns may hide declared JMH inputs: {name}")
        actual = collections.Counter((folder["type"], url_path(root, directory, folder["path"])) for folder in imported_content[0].get("sourceFolders", []))
        if generated != expected or actual != expected:
            fail(f"missing, duplicate, broader, wrong-owner or wrong-kind roots: {name}: expected={expected} generated={generated} imported={actual}")
        for excluded in manager.findall("./content/excludeFolder"):
            excluded_path = url_path(root, directory, excluded.attrib["url"], container_root, False)
            if any(excluded_path == path or excluded_path in path.parents for _, path in expected):
                fail(f"generated exclusion hides a declared root: {name}")
        for excluded in imported_content[0].get("excludeFolders", []):
            excluded_path = url_path(root, directory, excluded["path"], source=False)
            if any(excluded_path == path or excluded_path in path.parents for _, path in expected):
                fail(f"imported exclusion hides a declared root: {name}")
        sdks = [entry for entry in imported.get("orderEntries", []) if entry.get("type") == "SDK"]
        generated_sdks = manager.findall("orderEntry[@type='inheritedJdk']") + manager.findall("orderEntry[@type='jdk']")
        if len(sdks) != 1 or sdks[0].get("name") != sdk or len(generated_sdks) != 1 or project_java < owner["java"]:
            fail(f"missing or incompatible owner SDK: {name}")
        if generated_sdks[0].attrib.get("jdkName", sdk) != sdk:
            fail(f"wrong generated SDK: {name}")
        dependencies = owner["dependencies"]
        classpaths = owner["classpaths"]
        if set(classpaths) != {"compile", "runtime"} or set(dependencies) != set(classpaths.values()) or any(not closure for closure in dependencies.values()):
            fail(f"missing compile/runtime dependency closure: {name}")
        for configuration, closure in dependencies.items():
            for dependency in closure:
                if dependency["kind"] == "Module":
                    identity = dependency["identity"]
                    required_project = declarations_by_project.get(dependency["project"])
                    if not required_project or required_project["module"] != identity or len(modules[identity]) != 1:
                        fail(f"unresolved required project dependency: {name}: {identity}")
                    generated_dependencies = [entry for entry in manager.findall("orderEntry[@type='module']") if entry.attrib.get("module-name") == identity]
                    imported_dependencies = [entry for entry in imported["orderEntries"] if entry.get("type") == "Module" and entry.get("name") == identity]
                elif dependency["kind"] == "Library":
                    library = pathlib.Path(dependency["path"])
                    if not library.exists() or sha256(library) != dependency["sha256"]:
                        fail(f"unresolved or changed required library: {name}: {dependency['identity']}")
                    generated_dependencies = [entry for entry in manager.findall("orderEntry[@type='module-library']") if any(url_path(root, directory, item.attrib["url"], container_root, False) == library for item in entry.findall("./library/CLASSES/root"))]
                    imported_dependencies = [entry for entry in imported["orderEntries"] if entry.get("type") == "Library" and url_path(root, directory, entry.get("name"), source=False) == library]
                else:
                    fail(f"unsupported dependency kind: {dependency['kind']}")
                if len(generated_dependencies) != 1 or len(imported_dependencies) != 1:
                    fail(f"missing, duplicate or wrong-owner dependency: {name}: {dependency['identity']}")
                scopes = {"COMPILE", "TEST", "PROVIDED"} if configuration == classpaths["compile"] else {"COMPILE", "TEST", "RUNTIME"}
                if generated_dependencies[0].attrib.get("scope", "COMPILE") not in scopes:
                    fail(f"required dependency has an incompatible compile/runtime scope: {name}")
        # Explicitly shared declarations preserve native component and showcase inputs; undeclared attribution fails.
        for foreign_name, foreign_modules in modules.items():
            if foreign_name == name:
                continue
            for foreign in foreign_modules:
                for content in foreign.get("contentEntries", []):
                    for folder in content.get("sourceFolders", []):
                        relative = url_path(root, directory, folder["path"]).relative_to(root).as_posix()
                        owned_paths = {item["path"] for item in declarations if item["sourceSet"] == "jmh"}
                        foreign_path = pathlib.PurePosixPath(relative)
                        if not any(foreign_path == pathlib.PurePosixPath(path) or foreign_path in pathlib.PurePosixPath(path).parents or pathlib.PurePosixPath(path) in foreign_path.parents for path in owned_paths):
                            continue
                        if not declared_shared_root(foreign_name, foreign.get("contentEntries", []), folder["type"], root / relative):
                            fail(f"JMH root attributed to a foreign owner: {name}: {foreign_name}: {relative}")
        for entry in module_entries:
            foreign_iml = url_path(root, directory, entry.attrib["fileurl"], container_root, False)
            if foreign_iml == iml:
                continue
            foreign_manager, foreign_folders = read_iml(foreign_iml)
            foreign_directory = foreign_iml.parent
            for folder in foreign_folders:
                foreign_path = url_path(root, foreign_directory, folder.attrib["url"], container_root)
                relative = foreign_path.relative_to(root).as_posix()
                owned_paths = {repository_path(root, item["path"]) for item in declarations if item["sourceSet"] == "jmh"}
                if not any(foreign_path == path or foreign_path in path.parents or path in foreign_path.parents for path in owned_paths):
                    continue
                foreign_contents = [{"path": url_path(root, foreign_directory, item.attrib["url"], container_root, False).as_posix()} for item in foreign_manager.findall("content")]
                if not declared_shared_root(foreign_iml.stem, foreign_contents, iml_kind(folder), foreign_path):
                    fail(f"generated JMH root attributed to a foreign owner: {name}: {foreign_iml.stem}: {relative}")
    print(f"Verified {len(owners)} declaration-derived JMH owners, roots, SDKs and compile/runtime dependency closures against IDEA and Qodana.")


if __name__ == "__main__":
    try:
        verify(pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]), sys.argv[3], pathlib.Path(sys.argv[4]))
    except (ValueError, KeyError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
