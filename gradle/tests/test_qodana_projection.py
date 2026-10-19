"""Exercise canonical source ownership against malformed IDEA and imported analysis models."""

import copy
import importlib.util
import hashlib
import json
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("qodana_declarations", pathlib.Path(__file__).parents[1] / "verify-qodana-declarations.py")
DECLARATIONS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DECLARATIONS)


class QodanaProjectionTest(unittest.TestCase):
    """Keep forgery controls independent from the Gradle model projection implementation."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name).resolve()
        self.inventory = {"projects": [], "tracked": {}}
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
            compiler = dict(moduleName="fixture-" + name, jvmTarget="17", friends=[dict(path=main_output.as_posix(), exists=True, sha256=DECLARATIONS.sha256(main_output))])
            owner = dict(project=":" + name, module=name, directory=name, iml=f"{name}/{name}.iml", roots=roots, outputs=outputs, native=True, compiler=compiler)
            self.inventory["projects"].append(owner)
            document = ET.Element("module", type="JAVA_MODULE", version="4")
            manager = ET.SubElement(document, "component", name="NewModuleRootManager")
            ET.SubElement(manager, "content", url="file://$MODULE_DIR$/")
            contents = [dict(path=f"file://$PROJECT_DIR$/{name}/", sourceFolders=[])]
            if name == "alpha":
                for source in roots:
                    url = "file://$PROJECT_DIR$/" + source["path"]
                    content = ET.SubElement(manager, "content", url=url)
                    attributes = {"url": url, "type": "java-resource"} if source["kind"] == "Resource" else {"url": url, "isTestSource": "false"}
                    ET.SubElement(content, "sourceFolder", **attributes)
                    contents.append(dict(path=url, sourceFolders=[dict(type=source["kind"], path=url)]))
            libraries = []
            if name == "beta":
                for output in outputs:
                    entry = ET.SubElement(manager, "orderEntry", type="module-library")
                    if not output["production"]:
                        entry.set("scope", "TEST")
                    ET.SubElement(ET.SubElement(ET.SubElement(entry, "library"), "CLASSES"), "root", url="file://$PROJECT_DIR$/" + output["path"] + "/")
                    libraries.append(dict(type="Library", name=(self.root / output["path"]).as_posix()))
            facets = ET.SubElement(document, "component", name="FacetManager")
            facet = ET.SubElement(facets, "facet", type="kotlin-language", name="Kotlin")
            configuration = ET.SubElement(facet, "configuration", version="3", platform="JVM 17", useProjectSettings="false")
            arguments = ET.SubElement(configuration, "compilerArguments")
            ET.SubElement(arguments, "option", name="moduleName", value=compiler["moduleName"])
            ET.SubElement(arguments, "option", name="jvmTarget", value="17")
            friends = {main_output.as_posix()}
            if name == "beta":
                friends.update((self.root / output["path"]).as_posix() for output in outputs if output["classes"])
            array = ET.SubElement(ET.SubElement(arguments, "option", name="friendPaths"), "array")
            for friend in sorted(friends):
                ET.SubElement(array, "option", value=friend)
            self.write_iml(name, document)
            ET.SubElement(modules, "module", fileurl="file://$PROJECT_DIR$/" + owner["iml"])
            self.model["modules"].append(dict(name=name, contentEntries=contents, orderEntries=libraries))
        idea = self.root / ".idea"
        idea.mkdir()
        (idea / "modules.xml").write_text(ET.tostring(project, encoding="unicode"), encoding="utf-8")

    def files(self, path):
        """Hash authored sources or synthetic output identity for a single fixture directory."""
        return {file.relative_to(self.root).as_posix(): DECLARATIONS.sha256(file) for file in (self.root / path).rglob("*") if file.is_file()}

    def write_iml(self, name, document):
        """Write the production XML declaration without permitting parser-dependent encodings."""
        (self.root / name / (name + ".iml")).write_text('<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(document, encoding="unicode"), encoding="utf-8")

    def validate(self):
        """Run only the additional checker; the existing verifier remains independently covered."""
        return DECLARATIONS.verify_source_ownership(self.root, self.inventory, self.model)

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

    def test_complete_source_owner_and_exact_compiled_variant_pass(self):
        canonical = self.validate()
        self.assertEqual({("alpha", "Source"), ("alpha", "Resource")}, set(canonical.values()))

    def test_complete_compiler_membership_precedes_partial_physical_ownership(self):
        self.inventory["projects"][1]["native"] = False
        self.inventory["projects"][1]["roots"][1]["inputs"] = []
        self.validate()

    def test_qodana_binary_names_may_omit_the_scheme_but_retain_an_exact_ide_macro(self):
        original = copy.deepcopy(self.model)
        for prefix in ("$PROJECT_DIR$/beta/", "$MODULE_DIR$/"):
            with self.subTest(prefix=prefix):
                self.model = copy.deepcopy(original)
                for entry in self.model["modules"][1]["orderEntries"]:
                    relative = pathlib.Path(entry["name"]).relative_to(self.root / "beta").as_posix()
                    entry["name"] = prefix + relative + "/"
                self.validate()
                self.model = copy.deepcopy(original)

    def test_imported_forgeries_are_rejected(self):
        for mutation in ("missing-source", "duplicate-source", "wrong-kind", "outside-content", "missing-content", "duplicate-content", "broader-content", "pattern", "exclusion", "missing-module", "duplicate-module", "missing-binary", "duplicate-binary", "wrong-binary", "bare-source", "unknown-macro", "relative-binary", "aliased-binary"):
            with self.subTest(mutation=mutation):
                original = copy.deepcopy(self.model)
                alpha, beta = self.model["modules"]
                content = alpha["contentEntries"][1]
                if mutation == "missing-source": content["sourceFolders"] = []
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
                elif mutation == "missing-binary": beta["orderEntries"].pop()
                elif mutation == "duplicate-binary": beta["orderEntries"] *= 2
                elif mutation == "wrong-binary": beta["orderEntries"][0]["name"] = self.model["modules"][0]["name"]
                elif mutation == "bare-source": content["sourceFolders"][0]["path"] = "$PROJECT_DIR$/shared/kotlin"
                elif mutation == "unknown-macro": beta["orderEntries"][0]["name"] = "$OTHER_DIR$/beta/build/classes/kotlin/main/"
                elif mutation == "relative-binary": beta["orderEntries"][0]["name"] = "beta/build/classes/kotlin/main/"
                elif mutation == "aliased-binary": beta["orderEntries"][0]["name"] = "$PROJECT_DIR$/beta/../beta/build/classes/kotlin/main/"
                with self.assertRaises((ValueError, KeyError)):
                    self.validate()
                self.model = original

    def test_generated_forgeries_are_rejected(self):
        for mutation in ("duplicate-content", "nested-content", "qualified-content", "outside-content", "duplicate-source", "qualified-source", "missing-binary", "wrong-scope", "duplicate-binary", "module-name", "extra-friend", "missing-friend", "hidden-source", "duplicate-manager"):
            with self.subTest(mutation=mutation):
                name = "beta" if mutation in {"missing-binary", "wrong-scope", "duplicate-binary"} else "alpha"
                path = self.root / name / (name + ".iml")
                original = path.read_bytes()
                document = ET.fromstring(original)
                manager = document.find("component")
                content = manager.findall("content")[-1]
                folder = content.find("sourceFolder")
                entry = manager.find("orderEntry")
                if mutation == "duplicate-content": manager.append(copy.deepcopy(content))
                elif mutation == "nested-content": ET.SubElement(content, "content", url=content.attrib["url"])
                elif mutation == "qualified-content": content.tag = "{forged}content"
                elif mutation == "outside-content": content.remove(folder); manager.find("content").append(folder)
                elif mutation == "duplicate-source": content.append(copy.deepcopy(folder))
                elif mutation == "qualified-source": folder.tag = "{forged}sourceFolder"
                elif mutation == "missing-binary": manager.remove(entry)
                elif mutation == "wrong-scope": entry.set("scope", "TEST")
                elif mutation == "duplicate-binary": manager.append(copy.deepcopy(entry))
                elif mutation == "module-name": document.find(".//option[@name='moduleName']").set("value", "foreign-module")
                elif mutation == "extra-friend": ET.SubElement(document.find(".//array"), "option", value=(self.root / "beta/build/classes/kotlin/main").as_posix())
                elif mutation == "missing-friend": document.find(".//array").clear()
                elif mutation == "hidden-source": ET.SubElement(content, "excludeFolder", url=content.attrib["url"])
                elif mutation == "duplicate-manager": document.append(copy.deepcopy(manager))
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
        for mutation in ("undeclared-input", "duplicate-input", "untracked-source", "duplicate-root", "duplicate-output", "missing-output", "missing-output-record", "missing-resource-record", "output-kinds", "wrong-native"):
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
                with self.assertRaises(ValueError): self.validate()
                self.inventory = original


if __name__ == "__main__":
    unittest.main()
