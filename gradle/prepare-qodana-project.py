"""Prepare disposable IDEA metadata from structured Gradle output, independently of XML formatting."""

import collections
import copy
import json
import pathlib
import sys
import xml.etree.ElementTree as ET


ENTRY_ANNOTATIONS = (
    "org.spongepowered.asm.mixin.Mixin",
    "org.spongepowered.asm.mixin.Shadow",
    "org.spongepowered.asm.mixin.gen.Accessor",
    "org.spongepowered.asm.mixin.gen.Invoker",
    "org.spongepowered.asm.mixin.injection.Inject",
    "org.spongepowered.asm.mixin.injection.ModifyConstant",
    "com.llamalad7.mixinextras.injector.ModifyExpressionValue",
    "com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod",
    "com.llamalad7.mixinextras.injector.v2.WrapWithCondition",
)


def verify_project_sdk(project, java, source):
    """Require one project SDK using the catalog's Java language level before replacing metadata."""
    managers = project.findall("./component[@name='ProjectRootManager']")
    if len(managers) != 1 or managers[0].get("languageLevel") != f"JDK_{java}":
        raise ValueError(f"{source} does not declare the catalog's required Java {java} project SDK")


def prepare_project(root, project_inventory, java):
    """Validate discovered native owners and preserve every registered module, including common source sets."""
    configured = json.loads((root / "build/qodana/declarations.json").read_text(encoding="utf-8"))["projects"]
    located = [entry for entry in configured if entry.get("directory")]
    by_directory = {entry["directory"]: entry for entry in located}
    if len(by_directory) != len(located):
        raise ValueError("duplicate configured project directory")
    expected = project_inventory.read_text(encoding="utf-8").splitlines()
    if len(set(expected)) != len(expected) or any(directory not in by_directory for directory in expected):
        raise ValueError("discovered Minecraft projects differ from the configured inventory")
    project = ET.parse(root / "strata.ipr").getroot()
    managers = project.findall("./component[@name='ProjectModuleManager']")
    if len(managers) != 1 or len(managers[0].findall("modules")) != 1:
        raise ValueError("expected one generated IDEA module table")
    modules = managers[0].findall("./modules/module")
    urls = [entry.get("fileurl") for entry in modules]
    if any(not url or url != "file://" + entry.get("filepath", "") for url, entry in zip(urls, modules)) or len(set(urls)) != len(urls):
        raise ValueError("missing, inconsistent or duplicate generated IDEA module identity")
    counts = {}
    for owner in ("runtime", "integration"):
        prefix = owner + "/minecraft-fabric-"
        directories = [directory for directory in expected if directory.startswith(prefix)]
        if not directories or set(directories) != {directory for directory in by_directory if directory.startswith(prefix)}:
            raise ValueError(f"discovered Minecraft {owner} projects differ from the configured inventory")
        required = ["file://$PROJECT_DIR$/" + by_directory[directory]["iml"] for directory in directories]
        required += ["file://$PROJECT_DIR$/" + pathlib.PurePosixPath(by_directory[directory]["iml"]).with_name(
            test["sourceSet"] + "-" + pathlib.PurePosixPath(by_directory[directory]["iml"]).name).as_posix()
                     for directory in directories for test in by_directory[directory].get("testCompilations", [])]
        actual = [url for url in urls if url.startswith("file://$PROJECT_DIR$/" + prefix)]
        if collections.Counter(actual) != collections.Counter(required):
            raise ValueError(f"generated Minecraft {owner} modules differ from the discovered configured projects")
        counts[owner] = len(directories)
    backend = by_directory.get("runtime/minecraft-fonts-lwjgl")
    if backend is None or urls.count("file://$PROJECT_DIR$/" + backend["iml"]) != 1:
        raise ValueError("expected one configured CPU font backend module")
    write_project_metadata(root, project, java)
    print(f"Prepared Qodana IDEA metadata for {counts['runtime']} runtime and {counts['integration']} integration Minecraft modules, Java {java}, and the CPU font backend.")


def write_project_metadata(root, project, java):
    """Write the validated module table and framework entrypoints, retaining the required SDK checks."""
    verify_project_sdk(project, java, "The generated IDEA project")
    idea = root / ".idea"
    misc = idea / "misc.xml"
    if misc.is_file():
        verify_project_sdk(ET.parse(misc).getroot(), java, "The existing Qodana IDEA metadata")
    module_project = ET.Element("project", version="4")
    module_project.append(copy.deepcopy(project.find("./component[@name='ProjectModuleManager']")))
    settings = ET.Element("project", version="4")
    ET.SubElement(settings, "component", {"name": "ProjectRootManager", "version": "2", "languageLevel": f"JDK_{java}",
                                         "default": "true", "project-jdk-name": f"jbr-{java}", "project-jdk-type": "JavaSDK"})
    # Mixin invokes transformed entrypoints and writes shadow fields; retain framework ownership without source exclusions.
    entrypoints = ET.SubElement(settings, "component", name="EntryPointsManager")
    annotations = ET.SubElement(entrypoints, "list", size=str(len(ENTRY_ANNOTATIONS)))
    for index, annotation in enumerate(ENTRY_ANNOTATIONS):
        ET.SubElement(annotations, "item", {"index": str(index), "class": "java.lang.String", "itemvalue": annotation})
    ET.SubElement(ET.SubElement(entrypoints, "writeAnnotations"), "writeAnnotation", name="org.spongepowered.asm.mixin.Shadow")
    idea.mkdir(exist_ok=True)
    for name, document in (("modules.xml", module_project), ("misc.xml", settings)):
        ET.ElementTree(document).write(idea / name, encoding="UTF-8", xml_declaration=True)


if __name__ == "__main__":
    try:
        prepare_project(pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]), int(sys.argv[3]))
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        raise SystemExit(f"Qodana IDEA preparation failed: {error}") from error
