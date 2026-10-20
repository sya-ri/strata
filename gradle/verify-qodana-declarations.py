#!/usr/bin/env python3
"""Compare fresh Gradle declarations with generated IDEA and imported Qodana ownership."""

import collections
import csv
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
        files = {file.relative_to(path).as_posix(): sha256(file) for file in sorted(path.rglob("*"), key=lambda value: value.relative_to(path).as_posix()) if file.is_file()}
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
    # Imported binary-library names retain IDE macros after Qodana removes the URI scheme.
    schemes = ("file://", "jar://") if source else ("file://", "jar://", "")
    prefixes = {scheme + variable: parent for scheme in schemes for variable, parent in (("$PROJECT_DIR$/", root), ("$MODULE_DIR$/", module_dir))}
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


def read_iml(path, multiple_contents=False):
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
    contents = manager.findall("content")
    if not contents or (not multiple_contents and len(contents) != 1):
        fail(f"invalid generated owner content: {path}")
    sources = manager.findall("./content/sourceFolder")
    relevant = [item for item in module.iter() if item.tag.split("}")[-1] in {"content", "sourceFolder"}]
    if any(item.tag not in {"content", "sourceFolder"} for item in relevant) or len(relevant) != len(contents) + len(sources):
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


def analysis_owners(inventory):
    """Derive source-set ownership from Gradle declarations, independently of generated IDEA metadata."""
    result = []
    for owner in inventory["projects"]:
        if not owner.get("iml"):
            continue
        source_sets = owner.get("commonSourceSets", [])
        nodes = {source["name"]: source for source in source_sets}
        if len(nodes) != len(source_sets) or owner.get("defaultSourceSet") in nodes:
            fail(f"duplicate or default common source-set declaration: {owner['module']}")
        if nodes and (not isinstance(owner.get("defaultSourceSet"), str) or not owner.get("compiler")):
            fail(f"common source sets have no JVM compilation owner: {owner['module']}")
        dependencies = owner.get("sourceSetDependencies", [])
        if not isinstance(dependencies, list) or dependencies != sorted(set(dependencies)):
            fail(f"noncanonical JVM source-set refinements: {owner['module']}")
        assigned = {}
        for source in source_sets:
            for key in ("kotlinRoots", "resourceRoots", "kotlinInputs", "resourceInputs", "dependsOn"):
                values = source[key]
                if not isinstance(values, list) or values != sorted(set(values)):
                    fail(f"noncanonical common source-set inputs: {owner['module']}: {source['name']}")
            for kind, key in (("Source", "kotlinRoots"), ("Resource", "resourceRoots")):
                if not set(source[key]).issubset(root["path"] for root in owner["roots"] if root["kind"] == kind):
                    fail(f"common source-set root has no compiler declaration: {owner['module']}: {source['name']}")
            roots = []
            for root in owner["roots"]:
                if root["kind"] == "Source" and root["path"] in source["kotlinRoots"]:
                    selected = source["kotlinInputs"]
                elif root["kind"] == "Resource" and root["path"] in source["resourceRoots"]:
                    selected = source["resourceInputs"]
                else:
                    continue
                roots.append(dict(root, inputs=sorted(set(root["inputs"]).intersection(selected))))
            for kind, key in (("Source", "kotlinInputs"), ("Resource", "resourceInputs")):
                if {value for root in roots if root["kind"] == kind for value in root["inputs"]} != set(source[key]):
                    fail(f"common source-set selection differs from actual compiler inputs: {owner['module']}: {source['name']}")
            assigned[source["name"]] = roots
        active = {name for name, roots in assigned.items() if any(root["exists"] and not root["generated"] and root["inputs"] for root in roots)}
        reached = set()

        def resolve(names, ancestors=frozenset()):
            values = set()
            for name in names:
                if name not in nodes or name in ancestors:
                    fail(f"unknown or cyclic common source-set refinement: {owner['module']}: {name}")
                reached.add(name)
                inherited = resolve(nodes[name]["dependsOn"], ancestors | {name})
                values.update({name} if name in active else inherited)
            return sorted(values)

        refines = resolve(dependencies)
        if reached != set(nodes):
            fail(f"common source sets differ from the JVM refinement graph: {owner['module']}")
        moved = collections.defaultdict(set)
        for roots in assigned.values():
            for root in roots:
                moved[root["path"]].update(root["inputs"])
        retained = []
        for root in owner["roots"]:
            if root["kind"] not in {"Source", "Resource"} or root["path"] not in moved:
                retained.append(root)
            else:
                remaining = sorted(set(root["inputs"]) - moved[root["path"]])
                if remaining:
                    retained.append(dict(root, inputs=remaining))
        result.append(dict(owner, roots=retained, common=False, sourceSet=owner.get("defaultSourceSet"), refines=refines))
        for name in sorted(active):
            if name in {".", ".."} or any(char in name for char in "/\\:"):
                fail(f"noncanonical common source-set identity: {name}")
            module = owner["module"] + "." + name
            iml = pathlib.PurePosixPath(owner["iml"]).with_name(module + ".iml").as_posix()
            result.append(dict(owner, module=module, iml=iml, roots=assigned[name], common=True, sourceSet=name,
                               refines=resolve(nodes[name]["dependsOn"]), compiler=dict(owner["compiler"], friends=[])))
    if len({owner["module"] for owner in result}) != len(result) or len({owner["iml"] for owner in result}) != len(result):
        fail("analysis source-set module identities collide")
    return result


def verify_source_views(root, inventory):
    """Require exact fresh compiler-selected bytes in a stable, distinct view for each shared or filtered root."""
    sources = [(owner["module"], source) for owner in analysis_owners(inventory)
               for source in owner["roots"] if source["exists"] and not source["generated"] and source["kind"] in {"Source", "TestSource"}]
    contexts = collections.defaultdict(set)
    selected = collections.defaultdict(set)
    for name, source in sources:
        contexts[source["path"]].add(name)
        if selected[name].intersection(source["inputs"]):
            fail(f"compiler source roots overlap within {name}")
        selected[name].update(source["inputs"])
    expected = {}
    for name, source in sources:
        if len(contexts[source["path"]]) < 2 and set(source["inputs"]) == set(source["files"]):
            continue
        path = source["path"]
        view = "qodana-analysis-sources/" + hashlib.sha256(name.encode("utf-8")).hexdigest()[:12] + "/" + hashlib.sha256(path.encode("utf-8")).hexdigest()[:16]
        if any(not value.startswith(path + "/") for value in source["inputs"]):
            fail(f"compiler input is outside its declared source root: {name}: {path}")
        files = {value[len(path) + 1:]: source["files"][value] for value in source["inputs"]}
        expected[name, path] = dict(module=name, path=path, view=view, kind=source["kind"], files=files)
    receipt = read_json(root / "build/qodana/source-views.json")
    if set(receipt) != {"revision", "roots"} or receipt["revision"] != inventory["revision"]:
        fail("source-view receipt belongs to another revision or schema")
    actual = {(entry["module"], entry["path"]): entry for entry in receipt["roots"]}
    if len(actual) != len(receipt["roots"]) or actual != expected or len({entry["view"] for entry in actual.values()}) != len(actual):
        fail("source-view ownership or selected inputs differ from the compiler declarations")
    views = {}
    for identity, entry in expected.items():
        directory = repository_path(root, entry["view"])
        if not directory.is_dir():
            fail(f"missing compiler source view: {entry['view']}")
        paths = list(directory.rglob("*"))
        if any(path.is_symlink() or path.resolve() != path for path in paths):
            fail(f"aliased compiler source view: {entry['view']}")
        files = {path.relative_to(directory).as_posix(): sha256(path) for path in paths if path.is_file()}
        if files != entry["files"]:
            fail(f"compiler source-view membership or bytes changed: {entry['view']}")
        views[identity] = directory
    if any(name.startswith("qodana-analysis-sources/") for name in inventory["tracked"]):
        fail("generated analysis source views must not become tracked source")
    return views


def verify_source_view_scope(root, views):
    """Reject generated compiler inputs hidden by Git rules used by Qodana's recommended profile."""
    paths = sorted(path.relative_to(root).as_posix() for directory in views.values() for path in directory.rglob("*") if path.is_file())
    if not paths:
        return
    result = subprocess.run(["git", "check-ignore", "--no-index", "-z", "--stdin"], cwd=root,
                            input=("\0".join(paths) + "\0").encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode == 0:
        ignored = result.stdout.decode("utf-8").strip("\0").split("\0")
        fail(f"Git exclusions hide generated compiler inputs: {ignored[:3]}")
    if result.returncode != 1:
        fail("cannot verify Git visibility of generated compiler inputs")


def verify_source_ownership(root, inventory, model, container_root=""):
    """Verify exact source membership in every declared compiler context and its compiled bytes."""
    declarations = analysis_owners(inventory)
    projects = {owner["module"]: owner for owner in declarations}
    modules = {module["name"]: module for module in model["modules"]}
    if len(projects) != len(declarations) or len(modules) != len(model["modules"]) or set(projects) != set(modules):
        fail(f"imported modules differ from the complete configured Gradle model: missing={sorted(set(projects) - set(modules))}, extra={sorted(set(modules) - set(projects))}")
    membership = {}

    def files_at(path):
        """Read each physical directory once during this verification, without reusing prior acceptance evidence."""
        if path not in membership:
            membership[path] = {file.relative_to(root).as_posix(): sha256(file) for file in path.rglob("*") if file.is_file()} if path.is_dir() else {}
        return membership[path]

    source_owners = collections.defaultdict(set)
    for owner in inventory["projects"]:
        if not owner.get("iml"):
            continue
        name = owner["module"]
        if type(owner["native"]) is not bool:
            fail(f"invalid platform ownership: {name}")
        paths = []
        for source in owner["roots"]:
            path = repository_path(root, source["path"])
            paths.append(path)
            files = files_at(path)
            inputs = source["inputs"]
            if source["exists"] != path.is_dir() or source["files"] != files:
                fail(f"compiler root membership or bytes changed: {name}: {path}")
            if not isinstance(inputs, list) or len(set(inputs)) != len(inputs) or not set(inputs).issubset(files) or not source["sourceSets"]:
                fail(f"invalid actual compiler inputs: {name}: {path}")
            tracked = set(inventory["tracked"])
            if (source["generated"] and set(files).intersection(tracked)) or (not source["generated"] and not set(files).issubset(tracked)):
                fail(f"authored compiler input membership differs from the selected revision: {name}: {path}")
            if source["exists"] and not source["generated"]:
                kind = source["kind"]
                if kind not in {"Source", "TestSource", "Resource", "TestResource"}:
                    fail(f"unsupported compiler root kind: {kind}")
        if len(paths) != len(set(paths)):
            fail(f"duplicate declared compiler root: {name}")
    for name, owner in projects.items():
        for source in owner["roots"]:
            if source["exists"] and not source["generated"]:
                source_owners[repository_path(root, source["path"])].add((name, source["kind"]))
    views = verify_source_views(root, inventory)
    entries = ET.parse(root / ".idea/modules.xml").getroot().findall("./component[@name='ProjectModuleManager']/modules/module")
    actual_imls = [url_path(root, root, entry.attrib["fileurl"], container_root, False) for entry in entries]
    expected_imls = {repository_path(root, owner["iml"]) for owner in declarations}
    if len(actual_imls) != len(set(actual_imls)) or set(actual_imls) != expected_imls:
        fail("IDEA modules differ from the complete configured Gradle model")
    for name, owner in projects.items():
        directory = repository_path(root, owner["directory"]) if owner["directory"] else root
        iml = repository_path(root, owner["iml"])
        manager, folders = read_iml(iml, multiple_contents=True)
        expected = collections.Counter((kind, views.get((module, path.relative_to(root).as_posix()), path)) for path, owners in source_owners.items() for module, kind in owners if module == name)
        contents = manager.findall("content")
        common = owner.get("common", False)
        expected_contents = {path for _, path in expected} if common else {directory} | {path for _, path in expected if directory not in path.parents}
        generated_paths = [url_path(root, directory, item.attrib["url"], container_root, False) for item in contents]
        imported = modules[name]
        imported_contents = imported.get("contentEntries", [])
        imported_paths = [url_path(root, directory, item["path"], source=False) for item in imported_contents]
        if (not common and contents[0].attrib != {"url": "file://$MODULE_DIR$/"}) or len(generated_paths) != len(expected_contents) or set(generated_paths) != expected_contents or len(imported_paths) != len(expected_contents) or set(imported_paths) != expected_contents:
            fail(f"missing, duplicate, broader or wrong content ownership: {name}")
        generated = collections.Counter((iml_kind(folder), url_path(root, directory, folder.attrib["url"], container_root)) for folder in folders)
        actual = collections.Counter((folder["type"], url_path(root, directory, folder["path"])) for content in imported_contents for folder in content.get("sourceFolders", []))
        if generated != expected or actual != expected:
            fail(f"missing, duplicate, wrong-kind or wrong-owner compiler roots: {name}")
        for content, content_path in zip(contents, generated_paths):
            for folder in content.findall("sourceFolder"):
                path = url_path(root, directory, folder.attrib["url"], container_root)
                if path != content_path and content_path not in path.parents:
                    fail(f"generated compiler root outside its content owner: {name}: {path}")
        for content, content_path in zip(imported_contents, imported_paths):
            if content.get("excludePatterns", []):
                fail(f"imported patterns may hide compiler inputs: {name}")
            for folder in content.get("sourceFolders", []):
                path = url_path(root, directory, folder["path"])
                if path != content_path and content_path not in path.parents:
                    fail(f"imported compiler root outside its content owner: {name}: {path}")
        exclusions = [url_path(root, directory, item.attrib["url"], container_root, False) for item in manager.findall("./content/excludeFolder")]
        exclusions += [url_path(root, directory, item["path"], source=False) for content in imported_contents for item in content.get("excludeFolders", [])]
        if any(excluded == path or excluded in path.parents for excluded in exclusions for _, path in expected):
            fail(f"exclusion hides a declared compiler root: {name}")
        outputs = owner["outputs"]
        if len({item["path"] for item in outputs}) != len(outputs):
            fail(f"duplicate owned compiler output: {name}")
        for source in owner["roots"]:
            if source["exists"] and not source["generated"] and source["inputs"]:
                resource = source["kind"].endswith("Resource")
                if not any(output["sourceSet"] in source["sourceSets"] and output["classes"] != resource and output["exists"] and output["files"] for output in outputs):
                    fail(f"missing declared output for a compiler input: {name}: {source['path']}")
        for output in outputs:
            if type(output["classes"]) is not bool or type(output["production"]) is not bool:
                fail(f"invalid compiler output ownership: {name}")
            path = repository_path(root, output["path"])
            if output["exists"] != path.is_dir() or output["files"] != files_at(path):
                fail(f"owned compiler outputs are missing or changed: {name}: {path}")

        compiler = owner["compiler"]
        if compiler:
            if "explicitApi" not in compiler or compiler["explicitApi"] not in (None, "strict", "warning", "disable"):
                fail(f"missing or invalid explicit API compiler mode: {name}")
            document = ET.parse(iml).getroot()
            configurations = document.findall("./component[@name='FacetManager']/facet[@type='kotlin-language']/configuration")
            if len(configurations) != 1:
                fail(f"missing or duplicate Kotlin compiler identity: {name}")
            configuration = configurations[0]
            arguments = configuration.find("compilerArguments")
            options = arguments.findall("option") if arguments is not None else []
            values = {item.attrib["name"]: item.attrib.get("value") for item in options}
            refines = owner.get("refines", [])
            multiplatform = common or bool(refines)
            attributes = dict(version="3", platform="Common (experimental) " if common else "JVM " + compiler["jvmTarget"], useProjectSettings="false")
            if multiplatform:
                attributes.update(isHmppProject="true", externalProjectId=owner["project"])
            if len(values) != len(options) or values.get("moduleName") != compiler["moduleName"] or values.get("jvmTarget") != (None if common else compiler["jvmTarget"]) or values.get("explicitApi") != compiler["explicitApi"] or configuration.attrib != attributes:
                fail(f"incorrect Kotlin compiler identity: {name}")
            if values.get("multiPlatform") != ("true" if multiplatform else None) or values.get("expectActualClasses") != ("true" if compiler.get("expectActualClasses", False) else None):
                fail(f"incorrect Kotlin multiplatform compiler options: {name}")
            source_set = configuration.findtext("./sourceSets/sourceSet")
            kind = configuration.findtext("newMppModelJpsModuleKind")
            if source_set != (owner.get("sourceSet") if multiplatform else None) or kind != (("SOURCE_SET_HOLDER" if common else "COMPILATION_AND_SOURCE_SET_HOLDER") if multiplatform else None):
                fail(f"incorrect Kotlin source-set context: {name}")
            parent = next(declaration["module"] for declaration in inventory["projects"] if declaration["project"] == owner["project"])
            expected_refinements = [parent + "." + source for source in refines]
            if [entry.text for entry in configuration.findall("./dependsOnModuleNames/dependsOn")] != expected_refinements or configuration.findall("implements"):
                fail(f"incorrect Kotlin source-set refinements: {name}")
            common_modules = {declaration["module"] for declaration in declarations if declaration.get("common")}
            generated_refinements = [entry for entry in manager.findall("orderEntry") if entry.attrib.get("type") == "module" and entry.attrib.get("module-name") in common_modules]
            imported_refinements = [entry["name"] for entry in imported["orderEntries"] if entry.get("type") == "Module" and entry.get("name") in common_modules]
            if collections.Counter(entry.attrib.get("module-name") for entry in generated_refinements) != collections.Counter(expected_refinements) or collections.Counter(imported_refinements) != collections.Counter(expected_refinements) or any(entry.attrib != {"type": "module", "module-name": entry.attrib["module-name"], "exported": ""} for entry in generated_refinements):
                fail(f"missing, duplicate or wrong-scope Kotlin refinement edge: {name}")
            if configuration.findall("additionalVisibleModuleNames"):
                fail(f"source contexts must not add undeclared source-owner friends: {name}")
            friends = set()
            for friend in compiler["friends"]:
                path = pathlib.Path(friend["path"])
                if friend["exists"] != path.exists() or (path.exists() and friend["sha256"] != sha256(path)):
                    fail(f"missing or changed actual Kotlin friend input: {name}: {path}")
                if friend["exists"]:
                    friends.add(path.as_posix())
            observed = arguments.findall("./option[@name='friendPaths']/array/option")
            if len(observed) != len(friends) or {item.attrib["value"] for item in observed} != friends:
                fail(f"Kotlin friends differ from actual compiler inputs: {name}")
    return source_owners, views


def verify_report(sarif, revision):
    """Require explicit completion of every current-revision run without execution or semantic errors."""
    if not sarif["runs"]:
        fail("the analysis report does not belong to the selected declaration revision")
    for run in sarif["runs"]:
        if {entry.get("revisionId") for entry in run.get("versionControlProvenance", [])} != {revision}:
            fail("the analysis report does not belong to the selected declaration revision")
        if run.get("properties", {}).get("qodana.sanity.results"):
            fail("analysis contains semantic sanity problems")
        invocations = run.get("invocations", [])
        if not invocations:
            fail("the analysis invocation did not complete successfully")
        for invocation in invocations:
            if invocation.get("executionSuccessful") is not True:
                fail("the analysis invocation did not complete successfully")
            for notification in invocation.get("toolExecutionNotifications", []):
                if notification.get("level") == "error":
                    fail(f"analysis reported an error: {notification['message'].get('text', '')}")


def compiler_sdk_name(toolchain):
    """Identify the resolved compiler installation without substituting its bytecode target."""
    identity = "\0".join(str(toolchain[key]) for key in ("installation", "java", "runtimeVersion", "vendor"))
    return f"strata-compiler-{toolchain['java']}-{hashlib.sha256(identity.encode('utf-8')).hexdigest()[:12]}"


def verify_compiler_sdks(root, inventory, model, sdk_model):
    """Require each generated and imported JVM owner to use its independently declared compiler SDK."""
    bindings = {}
    expected = {}
    for owner in analysis_owners(inventory):
        if not owner.get("iml"):
            continue
        toolchain = owner["compilerToolchain"]
        if toolchain is None:
            if owner["compiler"] is not None:
                fail(f"missing Kotlin compiler toolchain: {owner['module']}")
            continue
        if type(toolchain["java"]) is not int or toolchain["java"] < 9:
            fail("invalid modular compiler SDK version")
        if not toolchain["kotlinCompilers"] and not toolchain["javaCompilers"]:
            fail("compiler toolchain has no configured compilation")
        if any(version != toolchain["java"] for version in toolchain["kotlinCompilers"].values()):
            fail("Kotlin compiler SDK differs from the project toolchain")
        if len(set(toolchain["javaCompilers"])) != len(toolchain["javaCompilers"]):
            fail("duplicate Java compiler declaration")
        name = compiler_sdk_name(toolchain)
        if name not in expected:
            home = pathlib.Path(toolchain["installation"])
            if not home.is_absolute() or home.resolve() != home or not home.is_dir():
                fail(f"compiler SDK installation is unavailable: {home}")
            executable = home / "bin" / ("java.exe" if os.name == "nt" else "java")
            modules = sorted(line.split("@", 1)[0] for line in subprocess.check_output([str(executable), "--list-modules"], text=True, stderr=subprocess.PIPE).splitlines())
            if "java.base" not in modules or len(set(modules)) != len(modules):
                fail(f"invalid compiler SDK modules: {home}")
            expected[name] = dict(name=name, java=toolchain["java"], runtimeVersion=toolchain["runtimeVersion"], installation=home.as_posix(), roots=[f"jrt://{home.as_posix()}!/{module}" for module in modules])
        bindings[owner["module"]] = name
        manager, _ = read_iml(repository_path(root, owner["iml"]), multiple_contents=True)
        entries = [entry for entry in manager.findall("orderEntry") if entry.attrib.get("type") in {"jdk", "inheritedJdk"}]
        if len(entries) != 1 or entries[0].attrib != dict(type="jdk", jdkName=name, jdkType="JavaSDK"):
            fail(f"wrong generated compiler SDK: {owner['module']}")
        imported = [module for module in model["modules"] if module["name"] == owner["module"]]
        if len(imported) != 1 or [entry.get("name") for entry in imported[0]["orderEntries"] if entry.get("type") == "SDK"] != [name]:
            fail(f"wrong imported compiler SDK: {owner['module']}")
    if not bindings:
        fail("missing JVM compiler SDK declarations")
    receipt = read_json(root / "build/qodana/compiler-sdks.json")
    if receipt["revision"] != inventory["revision"] or receipt["modules"] != bindings or sorted(receipt["sdks"], key=lambda item: item["name"]) != sorted(expected.values(), key=lambda item: item["name"]):
        fail("compiler SDK receipt differs from the selected Gradle declarations")
    table = ET.parse(root / "build/qodana/ide-config/options/jdk.table.xml").getroot()
    generated = table.findall("./component[@name='ProjectJdkTable']/jdk")
    # The running IDE also registers its own SDK for root or empty modules.
    # Every declared compiler must retain exactly one original entry and its exact module bindings.
    names = collections.Counter(entry.find("name").attrib["value"] for entry in generated)
    if table.tag != "application" or any(names[name] != 1 for name in expected):
        fail("generated compiler SDK table differs from Gradle declarations")
    for name, sdk in expected.items():
        generated_sdk = next(entry for entry in generated if entry.find("name").attrib["value"] == name)
        imported = [entry for entry in sdk_model["sdks"] if entry["name"] == name]
        version = f'java version "{sdk["runtimeVersion"]}"'
        if generated_sdk.find("type").attrib["value"] != "JavaSDK" or generated_sdk.find("version").attrib["value"] != version or generated_sdk.find("homePath").attrib["value"] != sdk["installation"]:
            fail(f"wrong generated compiler SDK identity: {name}")
        if collections.Counter(entry.attrib["url"] for entry in generated_sdk.findall("./roots/classPath/root/root")) != collections.Counter(sdk["roots"]):
            fail(f"wrong generated compiler SDK modules: {name}")
        if len(imported) != 1 or imported[0]["sdkType"] != "JavaSDK" or imported[0]["sdkVersion"] != version or collections.Counter(imported[0]["roots"]) != collections.Counter(sdk["roots"]):
            fail(f"wrong imported compiler SDK identity or modules: {name}")
    return bindings


def verify_model(root, model_path, container_root, java_path):
    """Verify declaration provenance and both downstream models, returning the model and report directory."""
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
    verify_report(sarif, revision)
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
    projected = analysis_owners(inventory) if any("commonSourceSets" in entry for entry in declared_projects) else [entry for entry in declared_projects if entry.get("iml")]
    declarations_by_module = {entry["module"]: entry for entry in projected}
    if len(declarations_by_module) != len(projected):
        fail("duplicate configured module identity")

    ownership_fields = {"native" in entry for entry in declared_projects}
    if ownership_fields == {False, True}:
        fail("partial compiler ownership declaration schema")
    source_owners, source_views = verify_source_ownership(root, inventory, model, container_root) if ownership_fields == {True} else (None, {})
    verify_source_view_scope(root, source_views)
    compiler_sdks = verify_compiler_sdks(root, inventory, model, read_json(report_directory / "projectStructure/SDKs.json")) if source_owners is not None else {}

    def analysis_path(module, path):
        """Preserve authored declarations while comparing their exact imported source views."""
        return source_views.get((module, path.relative_to(root).as_posix()), path)

    def declared_shared_root(module_name, contents, kind, path):
        """Permit exact roots shared by actual configured declarations, including native showcase inputs."""
        declaration = declarations_by_module.get(module_name)
        if declaration is None:
            directories = {url_path(root, root, content["path"], source=False) for content in contents}
            candidates = [entry for entry in declared_projects if entry.get("directory") and repository_path(root, entry["directory"]) in directories]
            if len(candidates) == 1:
                declaration = candidates[0]
        return declaration is not None and any(item["kind"] == kind and (repository_path(root, item["path"]) == path or analysis_path(declaration["module"], repository_path(root, item["path"])) == path) for item in declaration["roots"])

    project_model = root / ".idea/modules.xml"
    if project_model.is_symlink():
        fail("linked IDEA project metadata")
    module_entries = ET.parse(project_model).getroot().findall("./component[@name='ProjectModuleManager']/modules/module")
    sdk_model = ET.parse(root / ".idea/misc.xml").getroot().findall("./component[@name='ProjectRootManager']")
    if len(sdk_model) != 1:
        fail("missing project SDK")
    sdk = sdk_model[0].attrib["project-jdk-name"]
    project_java = int(sdk_model[0].attrib["languageLevel"][4:])
    dependency_indices = {}
    library_hashes = {}

    def dependency_index(name, manager, imported, directory):
        """Resolve each physical edge once, retaining separate entries for duplicate and scope checks."""
        if name not in dependency_indices:
            generated_index = collections.defaultdict(list)
            imported_index = collections.defaultdict(list)
            for entry in manager.findall("orderEntry"):
                if entry.attrib.get("type") == "module":
                    generated_index["Module", entry.attrib.get("module-name")].append(entry)
                elif entry.attrib.get("type") == "module-library":
                    paths = {url_path(root, directory, item.attrib["url"], container_root, False) for item in entry.findall("./library/CLASSES/root")}
                    for path in paths:
                        generated_index["Library", path].append(entry)
            for entry in imported["orderEntries"]:
                if entry.get("type") == "Module":
                    imported_index["Module", entry.get("name")].append(entry)
                elif entry.get("type") == "Library":
                    imported_index["Library", url_path(root, directory, entry.get("name"), source=False)].append(entry)
            dependency_indices[name] = generated_index, imported_index
        return dependency_indices[name]

    def verify_dependencies(owner, manager, imported, directory, production=False):
        """Check each real compiler/runtime closure without assuming auxiliary dependencies match main."""
        name = owner["module"]
        dependencies = owner["dependencies"]
        classpaths = owner["classpaths"]
        if set(classpaths) != {"compile", "runtime"} or set(dependencies) != set(classpaths.values()) or ("sourceSet" not in owner and any(not closure for closure in dependencies.values())):
            fail(f"missing compile/runtime dependency closure: {name}")
        generated_index, imported_index = dependency_index(name, manager, imported, directory)
        for configuration, closure in dependencies.items():
            for dependency in closure:
                if dependency["kind"] == "Module":
                    identity = dependency["identity"]
                    required_project = declarations_by_project.get(dependency["project"])
                    if not required_project or required_project["module"] != identity or len(modules[identity]) != 1:
                        fail(f"unresolved required project dependency: {name}: {identity}")
                    generated_dependencies = generated_index["Module", identity]
                    imported_dependencies = imported_index["Module", identity]
                elif dependency["kind"] == "Library":
                    library = pathlib.Path(dependency["path"])
                    if not library.exists():
                        fail(f"unresolved or changed required library: {name}: {dependency['identity']}")
                    # Fresh hashes belong only to this immutable verification snapshot, never to a later acceptance run.
                    if library not in library_hashes:
                        library_hashes[library] = sha256(library)
                    if library_hashes[library] != dependency["sha256"]:
                        fail(f"unresolved or changed required library: {name}: {dependency['identity']}")
                    generated_dependencies = generated_index["Library", library]
                    imported_dependencies = imported_index["Library", library]
                else:
                    fail(f"unsupported dependency kind: {dependency['kind']}")
                scopes = {"COMPILE", "PROVIDED"} if configuration == classpaths["compile"] else {"COMPILE", "RUNTIME"}
                actual_scopes = [entry.attrib.get("scope", "COMPILE") for entry in generated_dependencies]
                if "sourceSet" in owner:
                    if not production:
                        # PROVIDED is on IDEA's test runtime classpath as well as its test compile classpath.
                        scopes.update({"TEST", "PROVIDED"})
                    # IDEA retains distinct production-runtime and test edges; Qodana flattens their scope labels.
                    if not actual_scopes or not set(actual_scopes).issubset({"COMPILE", "TEST", "PROVIDED", "RUNTIME"}) or len(imported_dependencies) != len(actual_scopes) or len(set(actual_scopes)) != len(actual_scopes):
                        fail(f"missing, duplicate or wrong-owner compilation dependency: {name}: {dependency['identity']}")
                elif len(generated_dependencies) != 1 or len(imported_dependencies) != 1:
                    fail(f"missing, duplicate or wrong-owner dependency: {name}: {dependency['identity']}")
                else:
                    scopes.add("TEST")
                if not scopes.intersection(actual_scopes):
                    fail(f"required dependency has an incompatible compile/runtime scope: {name}: {configuration}: {dependency['identity']}")

    if source_owners is not None:
        for declaration in projected:
            if not declaration["iml"]:
                continue
            name = declaration["module"]
            compilations = declaration["productionCompilations"]
            if compilations and declaration["project"] + ":qodanaDependencyInventory" not in inventory["taskGraph"]:
                fail(f"missing project-owned dependency export task: {name}")
            expected = {entry["sourceSet"] for entry in declaration["outputs"] if entry["production"]}
            if len({entry["sourceSet"] for entry in compilations}) != len(compilations) or {entry["sourceSet"] for entry in compilations} != expected:
                fail(f"production compiler outputs and dependency declarations differ: {name}")
            directory = root / declaration["directory"] if declaration["directory"] else root
            manager, _ = read_iml(repository_path(root, declaration["iml"]), multiple_contents=True)
            generated_index, imported_index = dependency_index(name, manager, modules[name][0], directory)
            for output in declaration["outputs"]:
                path = repository_path(root, output["path"])
                if generated_index["Library", path] or imported_index["Library", path]:
                    fail(f"module sources duplicated by their compiled outputs: {name}: {output['path']}")
            for compilation in compilations:
                verify_dependencies(dict(compilation, module=name), manager, modules[name][0], directory, production=True)

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
        manager, folders = read_iml(iml, multiple_contents=source_owners is not None)
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
                expected.append((declaration["kind"], analysis_path(name, path)))
        expected = collections.Counter(expected)
        generated = collections.Counter((iml_kind(folder), url_path(root, directory, folder.attrib["url"], container_root)) for folder in folders)
        imported = modules[name][0]
        imported_content = imported.get("contentEntries", [])
        if (source_owners is None and len(imported_content) != 1) or url_path(root, directory, imported_content[0]["path"], source=False) != directory:
            fail(f"wrong imported content owner: {name}")
        if imported_content[0].get("excludePatterns", []):
            fail(f"imported owner patterns may hide declared JMH inputs: {name}")
        actual = collections.Counter((folder["type"], url_path(root, directory, folder["path"])) for content in imported_content for folder in content.get("sourceFolders", []))
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
        owner_sdk = compiler_sdks.get(name, sdk)
        if len(sdks) != 1 or sdks[0].get("name") != owner_sdk or len(generated_sdks) != 1 or project_java < owner["java"]:
            fail(f"missing or incompatible owner SDK: {name}")
        if generated_sdks[0].attrib.get("jdkName", sdk) != owner_sdk:
            fail(f"wrong generated SDK: {name}")
        verify_dependencies(owner, manager, imported, directory)
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
            foreign_manager, foreign_folders = read_iml(foreign_iml, multiple_contents=source_owners is not None)
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
    auxiliary = inventory["auxiliary"]
    identities = [(entry["project"], entry["sourceSet"]) for entry in auxiliary]
    expected_identities = {(entry["project"], name) for entry in declared_projects for name in entry["auxiliarySourceSets"]}
    if len(set(identities)) != len(identities) or set(identities) != expected_identities:
        fail("configured working auxiliary compilations and detailed inventory differ")
    auxiliary_by_module = collections.defaultdict(list)
    for owner in auxiliary:
        declaration = declarations_by_project.get(owner["project"])
        if not declaration or declaration["module"] != owner["module"] or declaration["directory"] != owner["directory"]:
            fail("auxiliary owner differs from the complete configured project model")
        auxiliary_by_module[owner["module"]].append(owner)
    for name, compilations in auxiliary_by_module.items():
        if len(modules[name]) != 1:
            fail(f"missing or duplicate auxiliary owner: {name}")
        owner = compilations[0]
        directory = repository_path(root, owner["directory"])
        iml = repository_path(root, owner["iml"])
        if iml.stem != name or any(entry["iml"] != owner["iml"] for entry in compilations):
            fail(f"wrong generated auxiliary owner: {name}")
        if sum(url_path(root, directory, entry.attrib["fileurl"], container_root, False) == iml for entry in module_entries) != 1:
            fail(f"generated project does not own exactly one auxiliary module: {name}")
        manager, folders = read_iml(iml, multiple_contents=source_owners is not None)
        imported = modules[name][0]
        contents = imported.get("contentEntries", [])
        if (source_owners is None and len(contents) != 1) or url_path(root, directory, contents[0]["path"], source=False) != directory or url_path(root, directory, manager.find("content").attrib["url"], container_root, False) != directory:
            fail(f"wrong auxiliary content owner: {name}")
        expected = set()
        for compilation in compilations:
            roots = compilation["roots"]
            if len({(entry["kind"], entry["path"]) for entry in roots}) != len(roots):
                fail(f"duplicate auxiliary root declaration: {name}")
            for entry in roots:
                path = repository_path(root, entry["path"])
                if entry["generated"] or entry["sourceSet"] != compilation["sourceSet"] or not declared_shared_root(name, contents, entry["kind"], path):
                    fail(f"auxiliary root is not an exact configured authored declaration: {name}: {path}")
                files = {file.relative_to(root).as_posix(): sha256(file) for file in path.rglob("*") if file.is_file()} if path.is_dir() else {}
                if entry["exists"] != path.is_dir() or entry["files"] != files or not set(files).issubset(current):
                    fail(f"auxiliary root membership, existence or bytes changed: {name}: {path}")
                if entry["exists"]:
                    expected.add((entry["kind"], analysis_path(name, path)))
            outputs = compilation["outputs"]
            if len({entry["path"] for entry in outputs}) != len(outputs) or not any(entry["files"] for entry in outputs):
                fail(f"working auxiliary compilation has no unique compiler outputs: {name}")
            for output in outputs:
                path = repository_path(root, output["path"])
                files = {file.relative_to(root).as_posix(): sha256(file) for file in path.rglob("*") if file.is_file()} if path.is_dir() else {}
                if output["exists"] != path.is_dir() or output["files"] != files:
                    fail(f"auxiliary compiler outputs are missing or changed: {name}: {path}")
                generated_index, imported_index = dependency_index(name, manager, imported, directory)
                generated_libraries = generated_index["Library", path]
                imported_libraries = imported_index["Library", path]
                if len(generated_libraries) != len(imported_libraries) or len(generated_libraries) > 1:
                    fail(f"auxiliary owned output is duplicated or differently attributed: {name}: {path}")
            verify_dependencies(compilation, manager, imported, directory)
        generated = collections.Counter((iml_kind(folder), url_path(root, directory, folder.attrib["url"], container_root)) for folder in folders)
        actual = collections.Counter((folder["type"], url_path(root, directory, folder["path"])) for content in contents for folder in content.get("sourceFolders", []))
        for observed in (generated, actual):
            if any(observed[pair] != 1 for pair in expected):
                fail(f"missing, duplicate or wrong-kind auxiliary root: {name}")
            for pair in observed:
                if any(pair[1] == path or pair[1] in path.parents or path in pair[1].parents for _, path in expected) and pair not in expected:
                    fail(f"broader or overlapping auxiliary root: {name}: {pair}")
        if contents[0].get("excludePatterns", []):
            fail(f"imported patterns may hide auxiliary inputs: {name}")
        exclusions = [url_path(root, directory, entry.attrib["url"], container_root, False) for entry in manager.findall("./content/excludeFolder")]
        exclusions += [url_path(root, directory, entry["path"], source=False) for entry in contents[0].get("excludeFolders", [])]
        if any(excluded == path or excluded in path.parents for excluded in exclusions for _, path in expected):
            fail(f"exclusion hides auxiliary inputs: {name}")
        java_owners = [entry for entry in java["modules"] if entry["name"] == name]
        sdks = [entry for entry in imported["orderEntries"] if entry.get("type") == "SDK"]
        generated_sdks = manager.findall("orderEntry[@type='inheritedJdk']") + manager.findall("orderEntry[@type='jdk']")
        if len(java_owners) != 1 or any(entry["java"] != java_owners[0].get("languageLevel") for entry in compilations) or manager.attrib.get("LANGUAGE_LEVEL", f"JDK_{project_java}") != f"JDK_{owner['java']}" or project_java < owner["java"]:
            fail(f"wrong auxiliary compiler language level: {name}")
        owner_sdk = compiler_sdks.get(name, sdk)
        if len(sdks) != 1 or sdks[0].get("name") != owner_sdk or len(generated_sdks) != 1 or generated_sdks[0].attrib.get("jdkName", sdk) != owner_sdk:
            fail(f"wrong auxiliary SDK: {name}")
        for entry in module_entries:
            foreign_iml = url_path(root, directory, entry.attrib["fileurl"], container_root, False)
            if foreign_iml == iml:
                continue
            foreign_manager, foreign_folders = read_iml(foreign_iml, multiple_contents=source_owners is not None)
            foreign_contents = [{"path": url_path(root, foreign_iml.parent, item.attrib["url"], container_root, False).as_posix()} for item in foreign_manager.findall("content")]
            for folder in foreign_folders:
                path = url_path(root, foreign_iml.parent, folder.attrib["url"], container_root)
                if any(path == owned or path in owned.parents or owned in path.parents for _, owned in expected) and not declared_shared_root(foreign_iml.stem, foreign_contents, iml_kind(folder), path):
                    fail(f"generated auxiliary root attributed to a foreign owner: {name}: {path}")
        for foreign_name, foreign_modules in modules.items():
            if foreign_name == name:
                continue
            for foreign in foreign_modules:
                for content in foreign.get("contentEntries", []):
                    for folder in content.get("sourceFolders", []):
                        path = url_path(root, directory, folder["path"])
                        if any(path == owned or path in owned.parents or owned in path.parents for _, owned in expected) and not declared_shared_root(foreign_name, foreign.get("contentEntries", []), folder["type"], path):
                            fail(f"imported auxiliary root attributed to a foreign owner: {name}: {path}")
    print(f"Verified {len(owners)} declaration-derived JMH owners and {len(auxiliary)} working auxiliary compilations against IDEA and Qodana.")
    return model, report_directory


def verify_analysis(root, model, report_directory):
    """Require resolved Kotlin source owners and actual Kotlin inspection work from this analysis invocation."""
    owners = {
        module["name"] for module in model["modules"]
        if any(folder["type"] in {"Source", "TestSource"} and any(file.is_file() for file in url_path(root, root, folder["path"]).rglob("*.kt"))
               for content in module.get("contentEntries", []) for folder in content.get("sourceFolders", []))
    }
    if not owners:
        fail("analysis has no declared Kotlin source owners")
    log_path = report_directory / "log/idea.log"
    statistics_path = report_directory / "log/qodana_inspections_summary.csv"
    for path in (log_path, statistics_path):
        if path.is_symlink() or not path.is_file():
            fail(f"missing regular analysis evidence: {path.name}")
    log = log_path.read_text(encoding="utf-8-sig")
    contexts = collections.defaultdict(set)
    for module in model["modules"]:
        for content in module.get("contentEntries", []):
            for folder in content.get("sourceFolders", []):
                if folder["type"] in {"Source", "TestSource"}:
                    contexts[url_path(root, root, folder["path"])].add(module["name"])
    if any(len(modules) != 1 for modules in contexts.values()):
        options = re.findall(r"JVM options: \[([^\n]+)\]", log)
        for key in ("intellij.platform.shared.source.support", "batch.inspections.inspect.all.code.insight.contexts"):
            if len(options) != 1 or not re.search(r"(?:^|, )-D" + re.escape(key) + r"=true(?:,|$)", options[0]):
                fail(f"shared Kotlin roots require the analysis JVM option {key}=true")
    unresolved = set(re.findall(r"No source roots detected for module '([^']+)'", log))
    if unresolved.intersection(owners):
        fail(f"IDE did not resolve Kotlin source owners: {sorted(unresolved.intersection(owners))}")
    performed = 0
    with statistics_path.open(encoding="utf-8-sig", newline="") as source:
        rows = csv.DictReader(source, delimiter=";")
        if not {"Inspection Language", "Times Inspection was Performed"}.issubset(rows.fieldnames or []):
            fail("missing Kotlin inspection statistics columns")
        for row in rows:
            if (row.get("Inspection Language") or "").casefold() == "kotlin":
                try:
                    count = int(row["Times Inspection was Performed"])
                except (TypeError, ValueError):
                    fail("invalid Kotlin inspection count")
                if count < 0:
                    fail("invalid Kotlin inspection count")
                performed += count
    if not performed:
        fail("the analysis performed no Kotlin inspections")
    print(f"Verified resolved sources for {len(owners)} Kotlin owners and {performed} Kotlin inspection invocations.")


def verify(root, model_path, container_root, java_path):
    """Require complete declaration/model evidence and actual Kotlin analysis without an acceptance bypass."""
    model, report_directory = verify_model(root, model_path, container_root, java_path)
    verify_analysis(root, model, report_directory)


if __name__ == "__main__":
    try:
        verify(pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]), sys.argv[3], pathlib.Path(sys.argv[4]))
    except (ValueError, KeyError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
