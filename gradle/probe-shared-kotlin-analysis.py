"""Prepare and inspect a small compiled reproducer without changing production analysis gates."""

import csv
import json
import pathlib
import shutil
import sys
import tomllib
import xml.etree.ElementTree as ET


repository = pathlib.Path(__file__).resolve().parent.parent
fixture = repository / "qodana-shared-analysis-fixture"
catalog = tomllib.loads((repository / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]


def write(path, text):
    """Write one reproducible source or build input into the disposable fixture."""
    target = fixture / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def prepare():
    """Compile the same caller against two independently compiled internal implementations."""
    write("settings.gradle.kts", 'rootProject.name = "shared-kotlin-analysis"\ninclude("old", "new")\n')
    write("build.gradle.kts", '''
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "KOTLIN_VERSION" apply false
}

subprojects {
    val fixtureName = name
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(JAVA_VERSION)
        explicitApi()
        compilerOptions {
            moduleName.set("fixture-$fixtureName")
            allWarningsAsErrors.set(true)
        }
        sourceSets.named("main") { kotlin.srcDir(rootProject.file("shared")) }
    }
    val main = extensions.getByType<SourceSetContainer>().named("main")
    tasks.register<JavaExec>("verifyFixture") {
        dependsOn("classes")
        classpath = main.get().runtimeClasspath
        mainClass.set("fixture.HostKt")
    }
    tasks.register("writeClasspaths") {
        dependsOn("classes")
        doLast {
            file("build/classpath.txt").writeText(main.get().runtimeClasspath.files.joinToString("\\n") { it.canonicalPath })
        }
    }
}
'''.replace("KOTLIN_VERSION", catalog["kotlin"]).replace("JAVA_VERSION", catalog["java-baseline"]))
    write("shared/Shared.kt", "package fixture\npublic fun entry(): Int = versionValue() + 3\n")
    for name, value in (("old", 10), ("new", 20)):
        write(f"{name}/src/main/kotlin/fixture/Value.kt", "@file:JvmSynthetic\npackage fixture\ninternal fun versionValue(): Int = " + str(value) + "\n" +
              ("private fun unusedCanary(): Int = 0\n" if name == "old" else ""))
        write(f"{name}/src/main/kotlin/fixture/Host.kt", "package fixture\npublic fun main() { check(entry() == " + str(value + 3) + ") }\n")
        write(f"standalone-{name}/settings.gradle.kts", f'rootProject.name = "fixture-{name}"\n')
        write(f"standalone-{name}/build.gradle.kts", f'''
plugins {{ kotlin("jvm") version "{catalog['kotlin']}" }}
repositories {{ mavenCentral() }}
kotlin {{
    jvmToolchain({catalog['java-baseline']})
    explicitApi()
    compilerOptions {{ moduleName.set("fixture-{name}") }}
    sourceSets.named("main") {{ kotlin.setSrcDirs(listOf(file("../{name}/src/main/kotlin"), file("../shared"))) }}
}}
''')


def export(mode):
    """Keep compiled source bytes unchanged while varying only the independently opened IDEA projects."""
    for name in ("old", "new"):
        module = ET.Element("module", type="JAVA_MODULE", version="4")
        manager = ET.SubElement(module, "component", name="NewModuleRootManager", LANGUAGE_LEVEL="JDK_" + catalog["java-baseline"])
        for path in (fixture / name / "src/main/kotlin", fixture / "shared"):
            url = "file://" + path.as_posix()
            ET.SubElement(ET.SubElement(manager, "content", url=url), "sourceFolder", url=url, isTestSource="false")
        ET.SubElement(manager, "orderEntry", type="inheritedJdk")
        ET.SubElement(manager, "orderEntry", type="sourceFolder", forTests="false")
        for path in (fixture / name / "build/classpath.txt").read_text(encoding="utf-8").splitlines():
            library_path = pathlib.Path(path)
            if library_path.suffix != ".jar":
                continue
            library = ET.SubElement(ET.SubElement(manager, "orderEntry", type="module-library"), "library")
            ET.SubElement(ET.SubElement(library, "CLASSES"), "root", url="jar://" + library_path.as_posix() + "!/")
            ET.SubElement(library, "SOURCES")
        facet = ET.SubElement(ET.SubElement(module, "component", name="FacetManager"), "facet", type="kotlin-language", name="Kotlin")
        configuration = ET.SubElement(facet, "configuration", version="3", platform="JVM " + catalog["java-baseline"], useProjectSettings="false")
        arguments = ET.SubElement(configuration, "compilerArguments")
        for option, value in (("moduleName", "fixture-" + name), ("jvmTarget", catalog["java-baseline"]), ("explicitApi", "strict")):
            ET.SubElement(arguments, "option", name=option, value=value)
        write(name + "/fixture-" + name + ".iml", ET.tostring(module, encoding="unicode"))
    groups = {"combined": [("old", "new")], "separate": [("old",), ("new",)], "single-old": [("old",)], "single-new": [("new",)]}.get(mode, [("old", "new")])
    roots = []
    for index, names in enumerate(groups):
        relative = f"views/{mode}-{index}/.idea"
        project = ET.Element("project", version="4")
        modules = ET.SubElement(ET.SubElement(project, "component", name="ProjectModuleManager"), "modules")
        for name in names:
            path = fixture / name / ("fixture-" + name + ".iml")
            ET.SubElement(modules, "module", fileurl="file://" + path.as_posix(), filepath=path.as_posix())
        write(relative + "/modules.xml", ET.tostring(project, encoding="unicode"))
        misc = ET.Element("project", version="4")
        ET.SubElement(misc, "component", name="ProjectRootManager", version="2", languageLevel="JDK_" + catalog["java-baseline"], **{"project-jdk-name": "jbr-" + catalog["java-minecraft"], "project-jdk-type": "JavaSDK"})
        write(relative + "/misc.xml", ET.tostring(misc, encoding="unicode"))
        project.append(misc[0])
        write(f"views/{mode}-{index}/view.ipr", ET.tostring(project, encoding="unicode"))
        roots.append((fixture / f"views/{mode}-{index}/view.ipr").as_posix())
    project_directory = fixture
    if mode == "gradle-combined":
        roots = [fixture.as_posix()]
    elif mode == "gradle-separate":
        roots = [(fixture / ("standalone-" + name)).as_posix() for name in ("old", "new")]
    elif mode == "separate":
        pass
    else:
        shutil.copytree(fixture / f"views/{mode}-0/.idea", fixture / ".idea", dirs_exist_ok=True)
        roots = [".idea"]
    project_directory.mkdir(parents=True, exist_ok=True)
    linter = next(line for line in (repository / "qodana.yaml").read_text(encoding="utf-8").splitlines() if line.startswith("linter:"))
    write(mode + ".yaml", 'version: "1.0"\n' + linter + '\nrootJavaProjects:\n' + "\n".join("  - " + root for root in roots) + '\nprofile:\n  base:\n    name: qodana.recommended\n  inspections:\n    - inspection: UnusedSymbol\n      enabled: true\nfailThreshold: 0\n')
    print("project-dir=" + project_directory.relative_to(repository).as_posix())


def inspect(mode, results):
    """Require actual semantic analysis and the genuine unused control before interpreting the reproducer."""
    report = json.loads((results / "qodana.sarif.json").read_text(encoding="utf-8"))
    findings = []
    for run in report["runs"]:
        assert not run.get("properties", {}).get("qodana.sanity.results"), "Semantic sanity must be clean."
        assert all(invocation.get("executionSuccessful") is True for invocation in run.get("invocations", [])), "Analysis must complete."
        findings.extend((item["ruleId"], item["locations"][0]["physicalLocation"]["artifactLocation"]["uri"], item["message"]["text"]) for item in run.get("results", []))
    rows = list(csv.DictReader((results / "log/qodana_inspections_summary.csv").open(encoding="utf-8-sig"), delimiter=";"))
    work = sum(int(row["Times Inspection was Performed"]) for row in rows if row["Inspection Language"].casefold() == "kotlin")
    assert 0 < work, "The fixture must really be analyzed."
    assert all(rule == "UnusedSymbol" for rule, _, _ in findings), findings
    canaries = [item for item in findings if '"unusedCanary"' in item[2]]
    if mode == "single-new":
        assert not findings, findings
    else:
        assert len(canaries) == 1, findings
        if mode == "single-old":
            assert findings == canaries, findings
    print(json.dumps(dict(mode=mode, kotlin_work=work, findings=findings), indent=2))


if sys.argv[1] == "prepare":
    prepare()
elif sys.argv[1] == "export":
    export(sys.argv[2])
else:
    inspect(sys.argv[2], pathlib.Path(sys.argv[3]))
