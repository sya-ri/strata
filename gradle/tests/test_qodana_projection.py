"""Exercise shared compiler contexts against malformed IDEA and imported analysis models."""

import copy
import importlib.util
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("qodana_declarations", pathlib.Path(__file__).parents[1] / "verify-qodana-declarations.py")
DECLARATIONS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DECLARATIONS)


class QodanaProjectionTest(unittest.TestCase):
    """Keep forgery controls independent from the Gradle model projection implementation."""

    def test_native_python_declared_target_rows_use_exact_lf_delimiters(self):
        script = (pathlib.Path(__file__).parents[1] / "verify-qodana-model.sh").read_text(encoding="utf-8")
        marker = '"$qodana_python" - "$project_root/build/qodana/declarations.json" > "$declared_target_source_roots" <<\'PY\'\n'
        exporter = script.split(marker, 1)[1].split("\nPY\n", 1)[0]
        inventory = self.root / "declared-rows.json"
        inventory.write_text(json.dumps(dict(projects=[
            dict(project=":runtime:minecraft-fabric-1.20", roots=[
                dict(kind="Source", path="runtime/shared/minecraft-fabric/resources/font-stb/src/main/kotlin", exists=True, generated=False),
                dict(kind="Source", path="runtime/build/generated", exists=True, generated=True),
                dict(kind="Source", path="runtime/absent", exists=False, generated=False),
            ]),
            dict(project=":integration:docs", roots=[dict(kind="Source", path="showcase/kotlin", exists=True, generated=False)]),
            dict(project=":unrelated", roots=[]),
        ])), encoding="utf-8")
        result = subprocess.run([sys.executable, "-c", exporter, str(inventory)], capture_output=True, check=True)
        self.assertEqual(
            b"runtime\t1.20\tSource\tfile://$PROJECT_DIR$/runtime/shared/minecraft-fabric/resources/font-stb/src/main/kotlin\n"
            b"docs\t-\tSource\tfile://$PROJECT_DIR$/showcase/kotlin\n", result.stdout)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name).resolve()
        self.inventory = {"revision": "a" * 40, "projects": [], "tracked": {}}
        self.model = {"modules": []}
        for path, text in (("shared/kotlin/Value.kt", "internal fun value(): Int = 19\n"),
                           ("beta/src/main/kotlin/Screen.kt", "internal fun screen(): Int = value()\n"),
                           ("shared/resources/value.txt", "fixture resource\n")):
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text, encoding="utf-8")
            self.inventory["tracked"][path] = DECLARATIONS.sha256(file)
        project = ET.Element("project")
        modules = ET.SubElement(ET.SubElement(project, "component", name="ProjectModuleManager"), "modules")
        for name in ("alpha", "beta"):
            directory = self.root / name
            directory.mkdir(exist_ok=True)
            roots = []
            for path, kind in (("shared/kotlin", "Source"), ("beta/src/main/kotlin", "Source"), ("shared/resources", "Resource")):
                files = self.files(path)
                roots.append(dict(path=path, kind=kind, exists=True, generated=False, files=files, inputs=list(files), sourceSets=["main"]))
            outputs = []
            for relative, production, classes, filename in (("classes/kotlin/main", True, True, "Value.class"),
                                                            ("classes/kotlin/test", False, True, "Test.class"),
                                                            ("resources/main", True, False, "value.txt")):
                path = f"{name}/build/{relative}"
                file = self.root / path / filename
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"deterministic identity; working compilation is covered by TestKit")
                outputs.append(dict(path=path, sourceSet="main" if production else "test", production=production, classes=classes, exists=True, files=self.files(path)))
            main_output = self.root / outputs[0]["path"]
            compiler = dict(moduleName="fixture-" + name, jvmTarget="17", explicitApi="strict" if name == "alpha" else None, friends=[dict(path=main_output.as_posix(), exists=True, sha256=DECLARATIONS.sha256(main_output))])
            owner = dict(project=":" + name, module=name, directory=name, iml=f"{name}/{name}.iml", roots=roots, outputs=outputs, native=True, compiler=compiler)
            self.inventory["projects"].append(owner)
            document = ET.Element("module", type="JAVA_MODULE", version="4")
            manager = ET.SubElement(document, "component", name="NewModuleRootManager")
            ET.SubElement(manager, "content", url="file://$MODULE_DIR$/")
            contents = [dict(path=f"file://$PROJECT_DIR$/{name}/", sourceFolders=[])]
            for source in roots:
                url = "file://$PROJECT_DIR$/" + source["path"]
                content = manager.find("content") if source["path"].startswith(name + "/") else ET.SubElement(manager, "content", url=url)
                attributes = {"url": url, "type": "java-resource"} if source["kind"] == "Resource" else {"url": url, "isTestSource": "false"}
                ET.SubElement(content, "sourceFolder", **attributes)
                if source["path"].startswith(name + "/"):
                    contents[0]["sourceFolders"].append(dict(type=source["kind"], path=url))
                else:
                    contents.append(dict(path=url, sourceFolders=[dict(type=source["kind"], path=url)]))
            facets = ET.SubElement(document, "component", name="FacetManager")
            facet = ET.SubElement(facets, "facet", type="kotlin-language", name="Kotlin")
            configuration = ET.SubElement(facet, "configuration", version="3", platform="JVM 17", useProjectSettings="false")
            arguments = ET.SubElement(configuration, "compilerArguments")
            ET.SubElement(arguments, "option", name="moduleName", value=compiler["moduleName"])
            ET.SubElement(arguments, "option", name="jvmTarget", value="17")
            if compiler["explicitApi"]:
                ET.SubElement(arguments, "option", name="explicitApi", value=compiler["explicitApi"])
            friends = {main_output.as_posix()}
            array = ET.SubElement(ET.SubElement(arguments, "option", name="friendPaths"), "array")
            for friend in sorted(friends):
                ET.SubElement(array, "option", value=friend)
            self.write_iml(name, document)
            ET.SubElement(modules, "module", fileurl="file://$PROJECT_DIR$/" + owner["iml"])
            self.model["modules"].append(dict(name=name, contentEntries=contents, orderEntries=[]))
        idea = self.root / ".idea"
        idea.mkdir()
        (idea / "modules.xml").write_text(ET.tostring(project, encoding="unicode"), encoding="utf-8")
        self.project_source_views()

    def view_path(self, module, path):
        """Use independently computed stable context identities in the fixture."""
        return "qodana-analysis-sources/" + hashlib.sha256(module.encode()).hexdigest()[:12] + "/" + hashlib.sha256(path.encode()).hexdigest()[:16]

    def project_source_views(self):
        """Write only the declared selected bytes, retaining source and resource kinds independently."""
        records = []
        contexts = {}
        for owner in self.inventory["projects"]:
            for source in owner["roots"]:
                if source["kind"] in {"Source", "TestSource"}:
                    contexts.setdefault(source["path"], set()).add(owner["module"])
        for owner in self.inventory["projects"]:
            name = owner["module"]
            document = ET.parse(self.root / owner["iml"]).getroot()
            manager = document.find("component")
            for content in manager.findall("content"):
                manager.remove(content)
            primary = ET.SubElement(manager, "content", url="file://$MODULE_DIR$/")
            contents = [dict(path=f"file://$PROJECT_DIR$/{name}/", sourceFolders=[])]
            for source in owner["roots"]:
                path = source["path"]
                if source["kind"] in {"Source", "TestSource"} and (len(contexts[path]) != 1 or set(source["inputs"]) != set(source["files"])):
                    path = self.view_path(name, source["path"])
                    directory = self.root / path
                    directory.mkdir(parents=True, exist_ok=True)
                    for file in directory.rglob("*"):
                        if file.is_file():
                            file.unlink()
                    selected = {value[len(source["path"]) + 1:]: source["files"][value] for value in source["inputs"]}
                    for relative in selected:
                        destination = directory / relative
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        destination.write_bytes((self.root / source["path"] / relative).read_bytes())
                    records.append(dict(module=name, path=source["path"], view=path, kind=source["kind"], files=selected))
                url = "file://$PROJECT_DIR$/" + path
                local = path.startswith(name + "/")
                content = primary if local else ET.SubElement(manager, "content", url=url)
                attributes = dict(url=url, type="java-resource") if source["kind"] == "Resource" else dict(url=url, isTestSource=str(source["kind"] == "TestSource").lower())
                ET.SubElement(content, "sourceFolder", **attributes)
                if local:
                    contents[0]["sourceFolders"].append(dict(type=source["kind"], path=url))
                else:
                    contents.append(dict(path=url, sourceFolders=[dict(type=source["kind"], path=url)]))
            self.write_iml(name, document)
            next(module for module in self.model["modules"] if module["name"] == name)["contentEntries"] = contents
        output = self.root / "build/qodana/source-views.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(dict(revision=self.inventory["revision"], roots=records)), encoding="utf-8")

    def files(self, path):
        """Hash authored sources or synthetic output identity for a single fixture directory."""
        return {file.relative_to(self.root).as_posix(): DECLARATIONS.sha256(file) for file in (self.root / path).rglob("*") if file.is_file()}

    def write_iml(self, name, document):
        """Write the production XML declaration without permitting parser-dependent encodings."""
        (self.root / name / (name + ".iml")).write_text('<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(document, encoding="unicode"), encoding="utf-8")

    def validate(self):
        """Run only the additional checker; the existing verifier remains independently covered."""
        return DECLARATIONS.verify_source_ownership(self.root, self.inventory, self.model)[0]

    def common_declaration(self):
        """Give one compiled owner a production common source set with independently selected inputs."""
        owner = self.inventory["projects"][0]
        owner.update(defaultSourceSet="jvmMain", sourceSetDependencies=["commonMain"], commonSourceSets=[dict(
            name="commonMain", dependsOn=[], kotlinRoots=["shared/kotlin"], resourceRoots=["shared/resources"],
            kotlinInputs=["shared/kotlin/Value.kt"], resourceInputs=["shared/resources/value.txt"])])
        return owner

    def test_production_common_sources_retain_one_owner_and_real_compiler_outputs(self):
        owner = self.common_declaration()
        projected = DECLARATIONS.analysis_owners(self.inventory)
        primary = next(item for item in projected if item["module"] == "alpha")
        common = next(item for item in projected if item["module"] == "alpha.commonMain")
        self.assertEqual(["commonMain"], primary["refines"])
        self.assertEqual(["beta/src/main/kotlin"], [item["path"] for item in primary["roots"]])
        self.assertEqual(["shared/kotlin", "shared/resources"], [item["path"] for item in common["roots"]])
        self.assertEqual("alpha/alpha.commonMain.iml", common["iml"])
        self.assertEqual(owner["outputs"], common["outputs"])
        self.assertEqual(owner["compiler"]["moduleName"], common["compiler"]["moduleName"])
        self.assertEqual([], common["compiler"]["friends"])

    def test_invalid_common_source_set_graphs_and_selections_are_rejected(self):
        owner = self.common_declaration()
        original = copy.deepcopy(owner)
        for mutation in ("duplicate", "cycle", "unknown", "unreachable", "default", "missing-default", "wrong-root", "wrong-selection", "duplicate-edge"):
            with self.subTest(mutation=mutation):
                owner.clear()
                owner.update(copy.deepcopy(original))
                source = owner["commonSourceSets"][0]
                if mutation == "duplicate": owner["commonSourceSets"].append(copy.deepcopy(source))
                elif mutation == "cycle": source["dependsOn"] = ["commonMain"]
                elif mutation == "unknown": owner["sourceSetDependencies"] = ["foreign"]
                elif mutation == "unreachable": owner["sourceSetDependencies"] = []
                elif mutation == "default": owner["defaultSourceSet"] = "commonMain"
                elif mutation == "missing-default": owner["defaultSourceSet"] = None
                elif mutation == "wrong-root": source["kotlinRoots"] = ["foreign/kotlin"]
                elif mutation == "wrong-selection": source["kotlinInputs"] = ["beta/src/main/kotlin/Screen.kt"]
                elif mutation == "duplicate-edge": owner["sourceSetDependencies"] *= 2
                with self.assertRaises(ValueError):
                    DECLARATIONS.analysis_owners(self.inventory)

    def test_empty_intermediate_source_sets_do_not_create_placeholder_modules(self):
        owner = self.common_declaration()
        owner["sourceSetDependencies"] = ["emptyMain"]
        owner["commonSourceSets"].append(dict(name="emptyMain", dependsOn=["commonMain"], kotlinRoots=[], resourceRoots=[], kotlinInputs=[], resourceInputs=[]))
        projected = DECLARATIONS.analysis_owners(self.inventory)
        self.assertEqual(["alpha", "alpha.commonMain", "beta"], [item["module"] for item in projected])
        self.assertEqual(["commonMain"], projected[0]["refines"])

    def test_directory_digest_matches_the_compilers_case_sensitive_relative_path_order(self):
        path = self.root / "directory-hash"
        metadata = path / "META-INF/module.kotlin_module"
        classes = path / "fixture/Value.class"
        metadata.parent.mkdir(parents=True)
        classes.parent.mkdir(parents=True)
        metadata.write_bytes(b"module metadata")
        classes.write_bytes(b"compiled class")
        declared = {"META-INF/module.kotlin_module": DECLARATIONS.sha256(metadata), "fixture/Value.class": DECLARATIONS.sha256(classes)}
        expected = hashlib.sha256(json.dumps(declared, separators=(",", ":")).encode("utf-8")).hexdigest()
        self.assertEqual(expected, DECLARATIONS.sha256(path))

    def test_each_declared_compiler_retains_the_shared_source_and_resource_contexts(self):
        owners = self.validate()
        self.assertEqual({("alpha", "Source"), ("beta", "Source")}, owners[self.root / "shared/kotlin"])
        self.assertEqual({("alpha", "Resource"), ("beta", "Resource")}, owners[self.root / "shared/resources"])

    def test_shared_input_kind_remains_specific_to_each_compiler_context(self):
        owner = self.inventory["projects"][1]
        owner["roots"][0].update(kind="TestSource", sourceSets=["test"])
        document = ET.parse(self.root / owner["iml"]).getroot()
        folder = next(folder for folder in document.findall("./component/content/sourceFolder") if folder.attrib["url"].endswith(self.view_path("beta", "shared/kotlin")))
        folder.set("isTestSource", "true")
        self.write_iml("beta", document)
        source = self.model["modules"][1]["contentEntries"][1]["sourceFolders"][0]
        source["type"] = "TestSource"
        receipt = self.root / "build/qodana/source-views.json"
        values = json.loads(receipt.read_text(encoding="utf-8"))
        next(entry for entry in values["roots"] if entry["module"] == "beta" and entry["path"] == "shared/kotlin")["kind"] = "TestSource"
        receipt.write_text(json.dumps(values), encoding="utf-8")
        owners = self.validate()
        self.assertEqual({("alpha", "Source"), ("beta", "TestSource")}, owners[self.root / "shared/kotlin"])
        source["type"] = "Source"
        with self.assertRaisesRegex(ValueError, "wrong-kind"):
            self.validate()

    def test_physical_containment_does_not_displace_a_shared_compiler_context(self):
        owner = self.inventory["projects"][1]
        owner["native"] = False
        owner["roots"][1]["inputs"] = []
        self.project_source_views()
        self.validate()

    def test_source_views_preserve_filters_without_importing_unselected_code(self):
        relative = "shared/kotlin/Excluded.kt"
        (self.root / relative).write_text("this source must remain excluded\n", encoding="utf-8")
        self.inventory["tracked"][relative] = DECLARATIONS.sha256(self.root / relative)
        for owner in self.inventory["projects"]:
            owner["roots"][0]["files"][relative] = self.inventory["tracked"][relative]
        self.project_source_views()
        self.validate()
        for name in ("alpha", "beta"):
            self.assertFalse((self.root / self.view_path(name, "shared/kotlin") / "Excluded.kt").exists())
        extra = self.root / self.view_path("alpha", "shared/kotlin") / "Excluded.kt"
        extra.write_bytes((self.root / relative).read_bytes())
        with self.assertRaisesRegex(ValueError, "source-view membership"):
            self.validate()

    def test_changed_or_missing_projected_source_bytes_are_rejected(self):
        path = self.root / self.view_path("alpha", "shared/kotlin") / "Value.kt"
        original = path.read_bytes()
        for mutation in ("changed", "missing"):
            with self.subTest(mutation=mutation):
                if mutation == "changed":
                    path.write_bytes(original + b"\nprivate fun injected() = 0\n")
                else:
                    path.unlink()
                with self.assertRaisesRegex(ValueError, "source-view membership"):
                    self.validate()
                path.write_bytes(original)

    def test_stale_collapsed_or_forged_source_view_receipts_are_rejected(self):
        path = self.root / "build/qodana/source-views.json"
        original = path.read_text(encoding="utf-8")
        for mutation in ("revision", "missing", "duplicate", "collapsed", "original-root", "membership"):
            with self.subTest(mutation=mutation):
                receipt = json.loads(original)
                if mutation == "revision": receipt["revision"] = "b" * 40
                elif mutation == "missing": receipt["roots"].pop()
                elif mutation == "duplicate": receipt["roots"].append(copy.deepcopy(receipt["roots"][0]))
                elif mutation == "collapsed": receipt["roots"][1]["view"] = receipt["roots"][0]["view"]
                elif mutation == "original-root": receipt["roots"][0]["view"] = receipt["roots"][0]["path"]
                else: receipt["roots"][0]["files"] = {}
                path.write_text(json.dumps(receipt), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "source-view"):
                    self.validate()
                path.write_text(original, encoding="utf-8")

    def test_generated_views_cannot_become_authored_tracked_source(self):
        relative = self.view_path("alpha", "shared/kotlin") + "/Value.kt"
        self.inventory["tracked"][relative] = DECLARATIONS.sha256(self.root / relative)
        with self.assertRaisesRegex(ValueError, "must not become tracked"):
            self.validate()

    def test_untracked_views_remain_visible_but_git_ignored_compiler_inputs_fail(self):
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        views = DECLARATIONS.verify_source_views(self.root, self.inventory)
        DECLARATIONS.verify_source_view_scope(self.root, views)
        for pattern in ("qodana-analysis-sources/", "*.kt"):
            with self.subTest(pattern=pattern):
                (self.root / ".gitignore").write_text(pattern + "\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Git exclusions hide generated compiler inputs"):
                    DECLARATIONS.verify_source_view_scope(self.root, views)

    def test_imported_forgeries_are_rejected(self):
        for mutation in ("missing-source", "missing-shared-context", "duplicate-source", "wrong-kind", "outside-content", "missing-content", "duplicate-content", "broader-content", "pattern", "exclusion", "missing-module", "duplicate-module", "bare-source"):
            with self.subTest(mutation=mutation):
                original = copy.deepcopy(self.model)
                alpha, beta = self.model["modules"]
                content = alpha["contentEntries"][1]
                if mutation == "missing-source": content["sourceFolders"] = []
                elif mutation == "missing-shared-context": beta["contentEntries"][1]["sourceFolders"] = []
                elif mutation == "duplicate-source": content["sourceFolders"] *= 2
                elif mutation == "wrong-kind": content["sourceFolders"][0]["type"] = "TestSource"
                elif mutation == "outside-content": alpha["contentEntries"][0]["sourceFolders"] = content.pop("sourceFolders")
                elif mutation == "missing-content": alpha["contentEntries"].pop()
                elif mutation == "duplicate-content": alpha["contentEntries"].append(copy.deepcopy(content))
                elif mutation == "broader-content": content["path"] = "file://$PROJECT_DIR$/shared/"
                elif mutation == "pattern": content["excludePatterns"] = ["*.kt"]
                elif mutation == "exclusion": content["excludeFolders"] = [dict(path=content["path"])]
                elif mutation == "missing-module": self.model["modules"].pop()
                elif mutation == "duplicate-module": self.model["modules"].append(copy.deepcopy(beta))
                elif mutation == "bare-source": content["sourceFolders"][0]["path"] = "$PROJECT_DIR$/shared/kotlin"
                with self.assertRaises((ValueError, KeyError)):
                    self.validate()
                self.model = original

    def test_generated_forgeries_are_rejected(self):
        for mutation in ("duplicate-content", "nested-content", "qualified-content", "outside-content", "duplicate-source", "qualified-source", "undeclared-source-friend", "module-name", "extra-friend", "missing-friend", "hidden-source", "duplicate-manager", "explicit-api", "missing-explicit-api"):
            with self.subTest(mutation=mutation):
                name = "alpha"
                path = self.root / name / (name + ".iml")
                original = path.read_bytes()
                document = ET.fromstring(original)
                manager = document.find("component")
                content = manager.findall("content")[-1]
                folder = content.find("sourceFolder")
                if mutation == "duplicate-content": manager.append(copy.deepcopy(content))
                elif mutation == "nested-content": ET.SubElement(content, "content", url=content.attrib["url"])
                elif mutation == "qualified-content": content.tag = "{forged}content"
                elif mutation == "outside-content": content.remove(folder); manager.find("content").append(folder)
                elif mutation == "duplicate-source": content.append(copy.deepcopy(folder))
                elif mutation == "qualified-source": folder.tag = "{forged}sourceFolder"
                elif mutation == "undeclared-source-friend": ET.SubElement(ET.SubElement(document.find(".//configuration"), "additionalVisibleModuleNames"), "friend").text = "beta"
                elif mutation == "module-name": document.find(".//option[@name='moduleName']").set("value", "foreign-module")
                elif mutation == "extra-friend": ET.SubElement(document.find(".//array"), "option", value=(self.root / "beta/build/classes/kotlin/main").as_posix())
                elif mutation == "missing-friend": document.find(".//array").clear()
                elif mutation == "hidden-source": ET.SubElement(content, "excludeFolder", url=content.attrib["url"])
                elif mutation == "duplicate-manager": document.append(copy.deepcopy(manager))
                elif mutation == "explicit-api": document.find(".//option[@name='explicitApi']").set("value", "warning")
                elif mutation == "missing-explicit-api": document.find(".//compilerArguments").remove(document.find(".//option[@name='explicitApi']"))
                self.write_iml(name, document)
                with self.assertRaises((ValueError, KeyError)):
                    self.validate()
                path.write_bytes(original)

    def test_changed_source_or_compiled_bytes_are_rejected(self):
        for relative in ("shared/kotlin/Value.kt", "beta/build/classes/kotlin/main/Value.class"):
            with self.subTest(path=relative):
                path = self.root / relative
                original = path.read_bytes()
                path.write_bytes(b"changed bytes")
                with self.assertRaises(ValueError): self.validate()
                path.write_bytes(original)

    def test_changed_compiler_membership_and_declarations_are_rejected(self):
        for mutation in ("undeclared-input", "duplicate-input", "untracked-source", "duplicate-root", "duplicate-output", "missing-output", "missing-output-record", "missing-resource-record", "output-kinds", "wrong-native", "explicit-api-mode", "missing-explicit-api-mode"):
            with self.subTest(mutation=mutation):
                original = copy.deepcopy(self.inventory)
                owner = self.inventory["projects"][1]
                if mutation == "undeclared-input": owner["roots"][0]["inputs"].append("missing.kt")
                elif mutation == "duplicate-input": owner["roots"][0]["inputs"] *= 2
                elif mutation == "untracked-source": self.inventory["tracked"] = {}
                elif mutation == "duplicate-root": owner["roots"].append(copy.deepcopy(owner["roots"][0]))
                elif mutation == "duplicate-output": owner["outputs"].append(copy.deepcopy(owner["outputs"][0]))
                elif mutation == "missing-output": owner["outputs"][0]["files"] = {}
                elif mutation == "missing-output-record": owner["outputs"].pop(0)
                elif mutation == "missing-resource-record": owner["outputs"].pop()
                elif mutation == "output-kinds": owner["outputs"][0]["production"] = "true"
                elif mutation == "wrong-native": owner["native"] = "true"
                elif mutation == "explicit-api-mode": owner["compiler"]["explicitApi"] = "unsupported"
                elif mutation == "missing-explicit-api-mode": owner["compiler"].pop("explicitApi")
                with self.assertRaises(ValueError): self.validate()
                self.inventory = original


if __name__ == "__main__":
    unittest.main()
