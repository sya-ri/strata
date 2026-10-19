"""Compare inherited and actual compiler SDKs in a disposable, independently compiled Kotlin control."""

import csv
import json
import pathlib
import shutil
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET


repository = pathlib.Path(__file__).resolve().parent.parent
fixture = repository / "qodana-sdk-analysis-fixture"
versions = tomllib.loads((repository / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]


def write(relative, content):
    """Write only the SDK control's fresh generated files, preserving the earlier shared-source fixture."""
    path = fixture / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def prepare():
    """Compile and execute Java 17-only and Java 25-only APIs on their real Gradle launchers."""
    write("settings.gradle.kts", 'rootProject.name = "qodana-sdk-control"\ninclude("old", "new")\n')
    write(".gitignore", 'build/\n')
    write("build.gradle.kts", '''
import groovy.json.JsonOutput
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins { kotlin("jvm") version "KOTLIN_VERSION" apply false }
val compilerVersions = mapOf("old" to OLD_JAVA, "new" to NEW_JAVA)
subprojects {
    val fixtureName = name
    val compilerVersion = compilerVersions.getValue(fixtureName)
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(compilerVersion))
    }
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(compilerVersion)
        explicitApi()
        compilerOptions {
            moduleName.set("sdk-$fixtureName")
            jvmTarget.set(JvmTarget.fromTarget(compilerVersion.toString()))
            allWarningsAsErrors.set(true)
        }
        sourceSets.named("main") { kotlin.srcDir(rootProject.file("shared")) }
    }
    val main = extensions.getByType<SourceSetContainer>().named("main")
    tasks.register<JavaExec>("verifyFixture") {
        dependsOn("classes")
        javaLauncher.set(launcher)
        classpath = main.get().runtimeClasspath
        mainClass.set("fixture.HostKt")
    }
    tasks.register("writeClasspaths") {
        dependsOn("classes")
        doLast {
            file("build/classpath.txt").writeText(main.get().runtimeClasspath.files.joinToString("\\n") { it.canonicalPath })
            val metadata = launcher.get().metadata
            file("build/toolchain.json").writeText(JsonOutput.toJson(mapOf(
                "java" to metadata.languageVersion.asInt(),
                "runtimeVersion" to metadata.javaRuntimeVersion,
                "installation" to metadata.installationPath.asFile.canonicalPath.replace(File.separatorChar, '/'),
            )))
        }
    }
}
'''.replace("KOTLIN_VERSION", versions["kotlin"]).replace("OLD_JAVA", versions["java-baseline"]).replace("NEW_JAVA", versions["java-minecraft"]))
    write("shared/Shared.kt", '''package fixture

/** Calls the selected module's independently compiled implementation. */
public fun entry(): Int = versionValue() + 3

private fun unusedSharedCanary(): Int = 0
''')
    write("old/src/main/kotlin/fixture/Value.kt", '''@file:Suppress("DEPRECATION") // The removed API is the SDK resolution control.

package fixture

import java.lang.Compiler

/** Resolves a real Java 17 API absent from the Java 25 SDK. */
internal fun versionValue(): Int = Compiler::class.java.simpleName.length + 2

private fun unusedSdkCanary(): Int = 0
''')
    write("new/src/main/kotlin/fixture/Value.kt", '''package fixture

import java.lang.ScopedValue

/** Resolves a real Java 25 API absent from the Java 17 SDK. */
internal fun versionValue(): Int = ScopedValue::class.java.simpleName.length + 9

private fun unusedNewSdkCanary(): Int = 0
''')
    for name, expected in (("old", 13), ("new", 23)):
        write(f"{name}/src/main/kotlin/fixture/Host.kt", f'''package fixture

/** Checks the compiled implementation on its actual toolchain JVM. */
public fun main() {{ check(entry() == {expected}) }}
''')


def export(mode):
    """Keep compiled bytes and classpaths fixed while changing only the IDEA SDK binding."""
    assert mode in {"inherited", "actual", "actual-shared", "actual-build"}
    application = ET.Element("application")
    table = ET.SubElement(application, "component", name="ProjectJdkTable")
    project = ET.Element("project", version="4")
    modules = ET.SubElement(ET.SubElement(project, "component", name="ProjectModuleManager"), "modules")
    expected = {}
    for name in ("old", "new"):
        metadata = json.loads((fixture / name / "build/toolchain.json").read_text(encoding="utf-8"))
        home = pathlib.Path(metadata["installation"])
        sdk_name = f"compiler-{name}-{metadata['java']}"
        expected[name] = dict(metadata, sdk=sdk_name)
        value = (fixture / name / "build/classes/kotlin/main/fixture/ValueKt.class").read_bytes()
        assert value[:4] == bytes.fromhex("CAFEBABE") and int.from_bytes(value[6:8], "big") == metadata["java"] + 44
        sdk = ET.SubElement(table, "jdk", version="2")
        for tag, value in (("name", sdk_name), ("type", "JavaSDK"), ("version", f'java version "{metadata["runtimeVersion"]}"'), ("homePath", home.as_posix())):
            ET.SubElement(sdk, tag, value=value)
        roots = ET.SubElement(sdk, "roots")
        classes = ET.SubElement(ET.SubElement(roots, "classPath"), "root", type="composite")
        java = home / "bin/java"
        if not java.is_file():
            java = home / "bin/java.exe"
        jdk_modules = sorted(line.split("@", 1)[0] for line in subprocess.check_output([str(java), "--list-modules"], text=True, stderr=subprocess.PIPE).splitlines())
        assert "java.base" in jdk_modules and len(set(jdk_modules)) == len(jdk_modules)
        for module_name in jdk_modules:
            ET.SubElement(classes, "root", type="simple", url=f"jrt://{home.as_posix()}!/{module_name}")
        sources = ET.SubElement(ET.SubElement(roots, "sourcePath"), "root", type="composite")
        if (home / "lib/src.zip").is_file():
            ET.SubElement(sources, "root", type="simple", url=f"jar://{home.as_posix()}/lib/src.zip!/")
        ET.SubElement(ET.SubElement(roots, "javadocPath"), "root", type="composite")
        ET.SubElement(sdk, "additional")
        module = ET.Element("module", type="JAVA_MODULE", version="4")
        manager = ET.SubElement(module, "component", name="NewModuleRootManager", LANGUAGE_LEVEL=f"JDK_{metadata['java']}")
        shared = fixture / "shared"
        if mode != "actual-shared":
            shared = fixture / "build/qodana/source-views" / name if mode == "actual-build" else fixture / name / "analysis-shared"
            shutil.copytree(fixture / "shared", shared, dirs_exist_ok=True)
            assert {path.relative_to(shared): path.read_bytes() for path in shared.rglob("*") if path.is_file()} == {
                path.relative_to(fixture / "shared"): path.read_bytes() for path in (fixture / "shared").rglob("*") if path.is_file()
            }
        for path in (fixture / name / "src/main/kotlin", shared):
            url = "file://" + path.as_posix()
            ET.SubElement(ET.SubElement(manager, "content", url=url), "sourceFolder", url=url, isTestSource="false")
        if mode != "inherited":
            ET.SubElement(manager, "orderEntry", type="jdk", jdkName=sdk_name, jdkType="JavaSDK")
        else:
            ET.SubElement(manager, "orderEntry", type="inheritedJdk")
        ET.SubElement(manager, "orderEntry", type="sourceFolder", forTests="false")
        for path in (fixture / name / "build/classpath.txt").read_text(encoding="utf-8").splitlines():
            if pathlib.Path(path).suffix == ".jar":
                library = ET.SubElement(ET.SubElement(manager, "orderEntry", type="module-library"), "library")
                ET.SubElement(ET.SubElement(library, "CLASSES"), "root", url="jar://" + pathlib.Path(path).as_posix() + "!/")
                ET.SubElement(library, "SOURCES")
        facet = ET.SubElement(ET.SubElement(module, "component", name="FacetManager"), "facet", type="kotlin-language", name="Kotlin")
        configuration = ET.SubElement(facet, "configuration", version="3", platform=f"JVM {metadata['java']}", useProjectSettings="false")
        arguments = ET.SubElement(configuration, "compilerArguments")
        for option, value in (("moduleName", "sdk-" + name), ("jvmTarget", str(metadata["java"])), ("explicitApi", "strict")):
            ET.SubElement(arguments, "option", name=option, value=value)
        iml = fixture / name / ("sdk-" + name + ".iml")
        write(iml.relative_to(fixture), ET.tostring(module, encoding="unicode"))
        ET.SubElement(modules, "module", fileurl="file://" + iml.as_posix(), filepath=iml.as_posix())
    root_module = ET.Element("module", type="JAVA_MODULE", version="4")
    root_manager = ET.SubElement(root_module, "component", name="NewModuleRootManager")
    root_content = ET.SubElement(root_manager, "content", url="file://" + fixture.as_posix() + "/")
    ET.SubElement(root_content, "excludeFolder", url="file://" + (fixture / "build").as_posix())
    ET.SubElement(root_manager, "orderEntry", type="jdk", jdkName=expected["new"]["sdk"], jdkType="JavaSDK")
    write("fixture-root.iml", ET.tostring(root_module, encoding="unicode"))
    ET.SubElement(modules, "module", fileurl="file://" + (fixture / "fixture-root.iml").as_posix(), filepath=(fixture / "fixture-root.iml").as_posix())
    write("ide-config/options/jdk.table.xml", ET.tostring(application, encoding="unicode"))
    write(".idea/modules.xml", ET.tostring(project, encoding="unicode"))
    misc = ET.Element("project", version="4")
    ET.SubElement(misc, "component", name="ProjectRootManager", version="2", languageLevel="JDK_" + versions["java-minecraft"], **{"project-jdk-name": "jbr-" + versions["java-minecraft"], "project-jdk-type": "JavaSDK"})
    write(".idea/misc.xml", ET.tostring(misc, encoding="unicode"))
    write("expected.json", json.dumps(expected, indent=2))
    linter = next(line for line in (repository / "qodana.yaml").read_text(encoding="utf-8").splitlines() if line.startswith("linter:"))
    write("probe.yaml", f'version: "1.0"\n{linter}\nrootJavaProjects:\n  - .idea\nprofile:\n  base:\n    name: qodana.recommended\n  inspections:\n    - inspection: UnusedSymbol\n      enabled: true\nfailThreshold: 0\n')


def inspect(mode, results):
    """Require resolved exclusive JDK APIs and genuine unused canaries before accepting an SDK model."""
    report = json.loads((results / "qodana.sarif.json").read_text(encoding="utf-8"))
    sanity = [item for run in report["runs"] for item in run.get("properties", {}).get("qodana.sanity.results", [])]
    findings = [item for run in report["runs"] for item in run.get("results", [])]
    assert all(run.get("invocations") and all(item.get("executionSuccessful") is True for item in run["invocations"]) for run in report["runs"])
    rows = list(csv.DictReader((results / "log/qodana_inspections_summary.csv").open(encoding="utf-8-sig"), delimiter=";"))
    assert 0 < sum(int(row["Times Inspection was Performed"]) for row in rows if row["Inspection Language"].casefold() == "kotlin")
    modules = json.loads((results / "projectStructure/Modules.json").read_text(encoding="utf-8"))["modules"]
    assert {module["name"] for module in modules} == {"sdk-old", "sdk-new", "fixture-root"}, modules
    root_module, = [module for module in modules if module["name"] == "fixture-root"]
    content, = root_module["contentEntries"]
    assert content["path"] in {"file://$PROJECT_DIR$", "file://$PROJECT_DIR$/"} and not content["sourceFolders"], content
    assert not content.get("excludePatterns") and [item["path"] for item in content["excludeFolders"]] == ["file://$PROJECT_DIR$/build"], content
    source_modules = [module for module in modules if module["name"] != "fixture-root"]
    for module in source_modules:
        name = module["name"].removeprefix("sdk-")
        shared = "shared" if mode == "actual-shared" else "build/qodana/source-views/" + name if mode == "actual-build" else name + "/analysis-shared"
        expected_roots = {"file://$PROJECT_DIR$/" + path for path in (name + "/src/main/kotlin", shared)}
        roots = [source for entry in module["contentEntries"] for source in entry["sourceFolders"]]
        assert {source["path"] for source in roots} == expected_roots and len(roots) == 2, roots
        assert all(source["type"] == "Source" and not source.get("packagePrefix") for source in roots), roots
        assert all(not entry.get("excludePatterns") and not entry.get("excludeFolders") for entry in module["contentEntries"]), module
    sdks = {module["name"]: [item["name"] for item in module["orderEntries"] if item.get("type") == "SDK"] for module in modules}
    print(json.dumps(dict(mode=mode, sdks=sdks, sanity=[item["message"]["text"] for item in sanity], findings=[(item["ruleId"], item["message"]["text"]) for item in findings]), indent=2))
    if mode == "inherited":
        assert sanity and "Compiler" in json.dumps(sanity), "The wrong-SDK control must expose the removed Java API."
        return
    expected = json.loads((fixture / "expected.json").read_text(encoding="utf-8"))
    assert sdks == {**{"sdk-" + name: [metadata["sdk"]] for name, metadata in expected.items()}, "fixture-root": [expected["new"]["sdk"]]}, sdks
    assert not sanity, "Actual SDKs must resolve both independently compiled JDK-specific APIs."
    messages = [item["message"]["text"] for item in findings]
    unused = [item for item in findings if item["ruleId"] == "UnusedSymbol"]
    removal = [item for item in findings if item["ruleId"] == "MarkedForRemoval"]
    assert len(findings) == len(unused) + len(removal) and len(removal) == 2, messages
    assert all("java.lang.Compiler" in item["message"]["text"] and item["locations"][0]["physicalLocation"]["artifactLocation"]["uri"] == "old/src/main/kotlin/fixture/Value.kt" for item in removal), messages
    assert sum('"unusedSdkCanary"' in message for message in messages) == 1 and sum('"unusedNewSdkCanary"' in message for message in messages) == 1, messages
    shared_count = sum('"unusedSharedCanary"' in message for message in messages)
    assert shared_count in ({1, 2} if mode == "actual-shared" else {2}), messages
    assert len(unused) == 2 + shared_count, messages
    shared_paths = {item["locations"][0]["physicalLocation"]["artifactLocation"]["uri"] for item in findings if '"unusedSharedCanary"' in item["message"]["text"]}
    if mode == "actual-shared":
        assert shared_paths == {"shared/Shared.kt"}, shared_paths
    else:
        assert shared_paths == {("build/qodana/source-views/" + name if mode == "actual-build" else name + "/analysis-shared") + "/Shared.kt" for name in ("old", "new")}, shared_paths


if sys.argv[1] == "prepare":
    prepare()
elif sys.argv[1] == "export":
    export(sys.argv[2])
else:
    inspect(sys.argv[2], pathlib.Path(sys.argv[3]))
