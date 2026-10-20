"""Check structured bootstrap metadata, including serialization changes and independent owner discovery."""

import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("qodana_preparation", pathlib.Path(__file__).parents[1] / "prepare-qodana-project.py")
PREPARATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARATION)


class QodanaPreparationTest(unittest.TestCase):
    """Exercise public preparation behavior without starting Gradle or claiming an analysis result."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        (self.root / "build/qodana").mkdir(parents=True)
        self.projects = []
        self.discovered = []
        self.document = ET.Element("project", version="4")
        ET.SubElement(self.document, "component", languageLevel="JDK_25", name="ProjectRootManager")
        modules = ET.SubElement(ET.SubElement(self.document, "component", name="ProjectModuleManager"), "modules")
        for owner in ("runtime", "integration"):
            for version in ("1.20", "26.3"):
                directory = f"{owner}/minecraft-fabric-{version}"
                self.discovered.append(directory)
                self.add_project(modules, directory, f"custom-{owner}-{version}.iml")
        self.add_project(modules, "runtime/minecraft-fonts-lwjgl", "custom-fonts.iml")
        self.add_project(modules, "api", "api.iml")
        ET.SubElement(modules, "module", filepath="$PROJECT_DIR$/api/api.commonMain.iml", fileurl="file://$PROJECT_DIR$/api/api.commonMain.iml")
        self.inventory = self.root / "projects.txt"
        self.inventory.write_text("\n".join(self.discovered) + "\n", encoding="utf-8")
        self.write_inputs(self.document, self.projects)

    def add_project(self, modules, directory, filename):
        """Give each real owner a nondefault module identity rather than inferring a filename."""
        iml = directory + "/" + filename
        self.projects.append(dict(directory=directory, iml=iml))
        path = "$PROJECT_DIR$/" + iml
        ET.SubElement(modules, "module", filepath=path, fileurl="file://" + path)

    def write_inputs(self, document, projects):
        """Write fresh independent declarations and the candidate generated project."""
        (self.root / "build/qodana/declarations.json").write_text(json.dumps(dict(projects=projects)), encoding="utf-8")
        ET.ElementTree(document).write(self.root / "strata.ipr", encoding="utf-8", xml_declaration=True)

    def test_retains_common_modules_and_framework_entries_for_reordered_and_minified_xml(self):
        for multiline in (False, True):
            with self.subTest(multiline=multiline):
                self.write_inputs(self.document, self.projects)
                if multiline:
                    project_file = self.root / "strata.ipr"
                    project_file.write_text(project_file.read_text(encoding="utf-8").replace("><", ">\n<"), encoding="utf-8")
                PREPARATION.prepare_project(self.root, self.inventory, 25)
                actual = ET.parse(self.root / ".idea/modules.xml").getroot().findall("./component/modules/module")
                self.assertEqual(self.document.findall("./component/modules/module")[0].attrib, actual[0].attrib)
                self.assertEqual(7, len(actual))
                self.assertIn("file://$PROJECT_DIR$/api/api.commonMain.iml", [entry.get("fileurl") for entry in actual])
                settings = ET.parse(self.root / ".idea/misc.xml").getroot()
                self.assertEqual("jbr-25", settings.find("./component[@name='ProjectRootManager']").get("project-jdk-name"))
                self.assertIsNotNone(settings.find(".//item[@itemvalue='org.spongepowered.asm.mixin.Mixin']"))
                self.assertIsNotNone(settings.find(".//writeAnnotation[@name='org.spongepowered.asm.mixin.Shadow']"))

    def test_rejects_missing_duplicate_unknown_or_renamed_native_modules_and_invalid_sdk(self):
        for mutation in ("missing-runtime", "duplicate-integration", "unknown-native", "renamed-native", "missing-font", "missing-table", "wrong-sdk"):
            with self.subTest(mutation=mutation):
                document = copy.deepcopy(self.document)
                modules = document.find("./component[@name='ProjectModuleManager']/modules")
                if mutation == "missing-runtime":
                    modules.remove(modules[0])
                elif mutation == "duplicate-integration":
                    modules.append(copy.deepcopy(modules[2]))
                elif mutation == "unknown-native":
                    path = "$PROJECT_DIR$/runtime/minecraft-fabric-99/extra.iml"
                    ET.SubElement(modules, "module", filepath=path, fileurl="file://" + path)
                elif mutation == "renamed-native":
                    path = "$PROJECT_DIR$/runtime/minecraft-fabric-1.20/undeclared.iml"
                    modules[0].set("filepath", path)
                    modules[0].set("fileurl", "file://" + path)
                elif mutation == "missing-font":
                    modules.remove(modules[4])
                elif mutation == "missing-table":
                    document.remove(document.find("./component[@name='ProjectModuleManager']"))
                else:
                    document.find("./component[@name='ProjectRootManager']").set("languageLevel", "JDK_17")
                self.write_inputs(document, self.projects)
                with self.assertRaises(ValueError):
                    PREPARATION.prepare_project(self.root, self.inventory, 25)

    def test_rejects_paired_omission_from_the_generated_and_configured_models(self):
        modules = self.document.find("./component[@name='ProjectModuleManager']/modules")
        modules.remove(modules[0])
        self.write_inputs(self.document, self.projects[1:])
        with self.assertRaisesRegex(ValueError, "discovered Minecraft projects"):
            PREPARATION.prepare_project(self.root, self.inventory, 25)

    def test_preserves_existing_sdk_validation_before_replacement(self):
        idea = self.root / ".idea"
        idea.mkdir()
        (idea / "misc.xml").write_text('<project><component name="ProjectRootManager" languageLevel="JDK_17"/></project>', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "existing Qodana IDEA metadata"):
            PREPARATION.prepare_project(self.root, self.inventory, 25)


if __name__ == "__main__":
    unittest.main()
