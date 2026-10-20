"""Compare Kotlin actual-property inspection with compiler and own-output context controls."""

import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

repository = pathlib.Path(__file__).resolve().parent.parent
fixture = repository / "qodana-multiplatform-analysis-fixture"
versions = tomllib.loads((repository / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]


def write(relative, value):
    path = fixture / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def prepare():
    write("settings.gradle.kts", 'rootProject.name = "multiplatform-control"\ninclude("plain", "mpp")\n')
    write("build.gradle.kts", '''
import groovy.json.JsonOutput
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

plugins {
    kotlin("jvm") version "__KOTLIN__" apply false
    kotlin("multiplatform") version "__KOTLIN__" apply false
}

fun Project.registerControl(main: KotlinCompilation<*>) {
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    tasks.register<JavaExec>("verifyFixture") {
        dependsOn(main.compileTaskProvider)
        javaLauncher.set(launcher)
        classpath(main.output.allOutputs, requireNotNull(main.runtimeDependencyFiles))
        mainClass.set("fixture.HostKt")
        doLast {
            val metadata = launcher.get().metadata
            file("build/model.json").writeText(JsonOutput.toJson(mapOf(
                "java" to metadata.languageVersion.asInt(),
                "runtimeVersion" to metadata.javaRuntimeVersion,
                "installation" to metadata.installationPath.asFile.canonicalPath.replace(File.separatorChar, '/'),
                "classpath" to main.compileDependencyFiles.files.map { it.canonicalPath.replace(File.separatorChar, '/') },
                "outputs" to main.output.classesDirs.files.map { it.canonicalPath.replace(File.separatorChar, '/') },
            )))
        }
    }
}
subprojects { repositories { mavenCentral() } }
project(":plain") {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    val kotlin = extensions.getByType<KotlinJvmProjectExtension>()
    kotlin.jvmToolchain(17)
    kotlin.compilerOptions.moduleName.set("fixture-plain")
    registerControl(kotlin.target.compilations.getByName("main"))
}
project(":mpp") {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
    kotlin.jvmToolchain(17)
    kotlin.compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    val main = kotlin.jvm().compilations.getByName("main")
    main.compileTaskProvider.configure { compilerOptions.moduleName.set("fixture-mpp") }
    registerControl(main)
}
'''.replace("__KOTLIN__", versions["kotlin"]))
    actual = '''package fixture

/** Isolates context on the executing JVM thread. */
internal ACTUALclass EvaluationContext<T : Any> ACTUALconstructor() {
    private val storage = ThreadLocal<T>()

    private fun unusedActualCanary(): Int = 0

    ACTUALvar current: T?
        get() = storage.get()
        set(value) {
            if (value == null) storage.remove() else storage.set(value)
        }
}

'''
    host = '''package fixture

/** Executes the compiled getter, setter and null release on the selected JVM. */
public fun main() {
    val context = EvaluationContext<String>()
    check(context.current == null)
    context.current = "active"
    check(context.current == "active")
    context.current = null
    check(context.current == null)
}
'''
    write("plain/src/main/kotlin/fixture/EvaluationContext.kt", actual.replace("ACTUAL", ""))
    write("plain/src/main/kotlin/fixture/Host.kt", host)
    write("mpp/src/jvmMain/kotlin/fixture/EvaluationContext.kt", actual.replace("ACTUAL", "actual "))
    write("mpp/src/jvmMain/kotlin/fixture/Host.kt", host)
    write("mpp/src/commonMain/kotlin/fixture/EvaluationContext.kt", '''package fixture

/** Holds an optional platform-owned synchronous context. */
internal expect class EvaluationContext<T : Any>() {
    var current: T?
}

private fun unusedCommonCanary(): Int = 0
''')


def export():
    metadata = json.loads((fixture / "mpp/build/model.json").read_text())
    plain = json.loads((fixture / "plain/build/model.json").read_text())
    assert metadata["installation"] == plain["installation"]
    assert metadata["java"] == 17
    home = pathlib.Path(metadata["installation"])
    java = home / "bin" / ("java.exe" if sys.platform == "win32" else "java")
    modules = sorted(line.split("@", 1)[0] for line in subprocess.check_output([str(java), "--list-modules"], text=True).splitlines())
    application = ET.Element("application")
    table = ET.SubElement(application, "component", name="ProjectJdkTable")
    sdk = ET.SubElement(table, "jdk", version="2")
    sdk_name = "fixture-compiler-17"
    for tag, value in (("name", sdk_name), ("type", "JavaSDK"), ("version", 'java version "' + metadata["runtimeVersion"] + '"'), ("homePath", home.as_posix())):
        ET.SubElement(sdk, tag, value=value)
    roots = ET.SubElement(sdk, "roots")
    classes = ET.SubElement(ET.SubElement(roots, "classPath"), "root", type="composite")
    for name in modules:
        ET.SubElement(classes, "root", type="simple", url="jrt://" + home.as_posix() + "!/" + name)
    for name in ("sourcePath", "javadocPath"):
        ET.SubElement(ET.SubElement(roots, name), "root", type="composite")
    ET.SubElement(sdk, "additional")
    write("ide-config/options/jdk.table.xml", ET.tostring(application, encoding="unicode"))
    project = ET.Element("project", version="4")
    registrations = ET.SubElement(ET.SubElement(project, "component", name="ProjectModuleManager"), "modules")
    cases = {
        "plain": ("plain", False, False),
        "merged": ("mpp", True, False),
        "no-self-output": ("mpp", False, False),
        "multiplatform-option": ("mpp", True, True),
        "multiplatform-no-self": ("mpp", False, True),
        "split-legacy": ("mpp", False, True),
        "split-hmpp": ("mpp", False, True),
    }
    evidence = {}
    for name, (compiled, own_output, multi_platform) in cases.items():
        model = plain if compiled == "plain" else metadata
        source_sets = ["main"] if compiled == "plain" else ["commonMain", "jvmMain"]
        if name.startswith("split-"):
            source_sets = ["jvmMain"]
        module = ET.Element("module", type="JAVA_MODULE", version="4")
        manager = ET.SubElement(module, "component", name="NewModuleRootManager", LANGUAGE_LEVEL="JDK_17")
        hashes = {}
        for source_set in source_sets:
            original = fixture / compiled / "src" / source_set / "kotlin"
            view = fixture / "analysis" / name / source_set
            shutil.copytree(original, view, dirs_exist_ok=True)
            url = "file://" + view.as_posix()
            ET.SubElement(ET.SubElement(manager, "content", url=url), "sourceFolder", url=url, isTestSource="false")
            for source in view.rglob("*.kt"):
                hashes[source_set + "/" + source.relative_to(view).as_posix()] = hashlib.sha256(source.read_bytes()).hexdigest()
        ET.SubElement(manager, "orderEntry", type="sourceFolder", forTests="false")
        ET.SubElement(manager, "orderEntry", type="jdk", jdkName=sdk_name, jdkType="JavaSDK")
        libraries = [(path, False) for path in model["classpath"]]
        if own_output:
            libraries.extend((path, True) for path in model["outputs"])
        for path, test in libraries:
            entry = ET.SubElement(manager, "orderEntry", type="module-library", **({"scope": "TEST"} if test else {}))
            library = ET.SubElement(entry, "library")
            file = pathlib.Path(path)
            url = ("jar://" + file.as_posix() + "!/") if file.suffix == ".jar" else ("file://" + file.as_posix())
            ET.SubElement(ET.SubElement(library, "CLASSES"), "root", url=url)
            ET.SubElement(library, "SOURCES")
        facet = ET.SubElement(ET.SubElement(module, "component", name="FacetManager"), "facet", type="kotlin-language", name="Kotlin")
        configuration = ET.SubElement(facet, "configuration", version="3", platform="JVM 17", useProjectSettings="false")
        arguments = ET.SubElement(configuration, "compilerArguments")
        for key, value in (("moduleName", "fixture-" + compiled), ("jvmTarget", "17")):
            ET.SubElement(arguments, "option", name=key, value=value)
        if multi_platform:
            ET.SubElement(arguments, "option", name="multiPlatform", value="true")
            ET.SubElement(arguments, "option", name="expectActualClasses", value="true")
        if name.startswith("split-"):
            common_name = name + "-common"
            common_view = fixture / "analysis" / common_name / "commonMain"
            shutil.copytree(fixture / "mpp/src/commonMain/kotlin", common_view, dirs_exist_ok=True)
            common_module = ET.Element("module", type="JAVA_MODULE", version="4")
            common_manager = ET.SubElement(common_module, "component", name="NewModuleRootManager")
            common_url = "file://" + common_view.as_posix()
            ET.SubElement(ET.SubElement(common_manager, "content", url=common_url), "sourceFolder", url=common_url, isTestSource="false")
            ET.SubElement(common_manager, "orderEntry", type="sourceFolder", forTests="false")
            ET.SubElement(common_manager, "orderEntry", type="jdk", jdkName=sdk_name, jdkType="JavaSDK")
            for entry in manager.findall("orderEntry[@type='module-library']"):
                import copy
                common_manager.append(copy.deepcopy(entry))
            common_facet = ET.SubElement(ET.SubElement(common_module, "component", name="FacetManager"), "facet", type="kotlin-language", name="Kotlin")
            common_configuration = ET.SubElement(common_facet, "configuration", version="3", platform="Common (experimental) ", useProjectSettings="false")
            common_arguments = ET.SubElement(common_configuration, "compilerArguments")
            ET.SubElement(common_arguments, "option", name="moduleName", value=common_name)
            ET.SubElement(common_arguments, "option", name="multiPlatform", value="true")
            ET.SubElement(manager, "orderEntry", type="module", **{"module-name": common_name})
            if name == "split-legacy":
                ET.SubElement(ET.SubElement(configuration, "implements"), "implement").text = common_name
            else:
                ET.SubElement(ET.SubElement(configuration, "dependsOnModuleNames"), "dependsOn").text = common_name
                for config, kind, source_set, view in (
                    (common_configuration, "SOURCE_SET_HOLDER", "commonMain", common_view),
                    (configuration, "COMPILATION_AND_SOURCE_SET_HOLDER", "jvmMain", fixture / "analysis" / name / "jvmMain"),
                ):
                    config.set("isHmppProject", "true")
                    config.set("externalProjectId", name)
                    config.set("pureKotlinSourceFolders", view.as_posix())
                    ET.SubElement(config, "newMppModelJpsModuleKind").text = kind
                    ET.SubElement(ET.SubElement(config, "sourceSets"), "sourceSet").text = source_set
            common_iml = fixture / (common_name + ".iml")
            write(common_iml.name, ET.tostring(common_module, encoding="unicode"))
            ET.SubElement(registrations, "module", fileurl="file://" + common_iml.as_posix(), filepath=common_iml.as_posix())
            evidence[common_name] = dict(sourceHashes={path.relative_to(common_view).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in common_view.rglob("*.kt")})
        iml = fixture / (name + ".iml")
        write(iml.name, ET.tostring(module, encoding="unicode"))
        ET.SubElement(registrations, "module", fileurl="file://" + iml.as_posix(), filepath=iml.as_posix())
        compiled_class = next(pathlib.Path(path) / "fixture/EvaluationContext.class" for path in model["outputs"] if (pathlib.Path(path) / "fixture/EvaluationContext.class").is_file())
        bytecode = compiled_class.read_bytes()
        assert bytecode[:4] == bytes.fromhex("CAFEBABE") and int.from_bytes(bytecode[6:8], "big") == 61
        evidence[name] = dict(ownOutput=own_output, multiPlatform=multi_platform, sourceHashes=hashes, compiledClassSha256=hashlib.sha256(bytecode).hexdigest())
    write(".idea/modules.xml", ET.tostring(project, encoding="unicode"))
    misc = ET.Element("project", version="4")
    ET.SubElement(misc, "component", name="ProjectRootManager", version="2", languageLevel="JDK_17", **{"project-jdk-name": sdk_name, "project-jdk-type": "JavaSDK"})
    write(".idea/misc.xml", ET.tostring(misc, encoding="unicode"))
    linter = next(line for line in (repository / "qodana.yaml").read_text().splitlines() if line.startswith("linter:"))
    write("probe.yaml", 'version: "1.0"\n' + linter + '\nrootJavaProjects:\n  - .idea\nprofile:\n  base:\n    name: qodana.recommended\nfailThreshold: 0\n')
    assert len({json.dumps(row["sourceHashes"], sort_keys=True) for name, row in evidence.items() if name != "plain" and not name.startswith("split-")}) == 1
    write("expected.json", json.dumps(evidence, indent=2) + "\n")


def inspect(results):
    report = json.loads((results / "qodana.sarif.json").read_text())
    findings = [item for run in report["runs"] for item in run.get("results", [])]
    assert report["runs"] and all(run.get("invocations") and all(item.get("executionSuccessful") is True for item in run["invocations"]) for run in report["runs"])
    modules = json.loads((results / "projectStructure/Modules.json").read_text())["modules"]
    expected = json.loads((fixture / "expected.json").read_text())
    assert {module["name"] for module in modules} == set(expected)
    for module in modules:
        assert [entry["name"] for entry in module["orderEntries"] if entry["type"] == "SDK"] == ["fixture-compiler-17"]
        assert all("analysis/" + module["name"] + "/" in source["path"] for content in module["contentEntries"] for source in content["sourceFolders"])
    rows = [dict(rule=item["ruleId"], message=item["message"]["text"], path=item["locations"][0]["physicalLocation"]["artifactLocation"]["uri"]) for item in findings]
    write("observed.json", json.dumps(rows, indent=2) + "\n")
    for name in json.loads((fixture / "expected.json").read_text()):
        selected = [row for row in rows if row["path"].startswith("analysis/" + name + "/")]
        canary = "unusedCommonCanary" if name.endswith("-common") else "unusedActualCanary"
        assert any(canary in row["message"] for row in selected), (name, selected)
        print(name, json.dumps(selected))


if __name__ == "__main__":
    if sys.argv[1] == "prepare":
        prepare()
    elif sys.argv[1] == "export":
        export()
    else:
        inspect(pathlib.Path(sys.argv[2]))
