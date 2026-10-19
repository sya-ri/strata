package dev.s7a.strata.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Runs production compiler exports and declaration inventory against working, Minecraft-independent owners. */
internal class QodanaJmhModelFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }

    @Test
    fun `discover custom working owners and retain all declared inputs and dependency mappings`() {
        Files.createDirectories(directory.resolve("gradle"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        Files.copy(repository.resolve("gradle/qodana-idea-model.gradle.kts"), directory.resolve("gradle/qodana-idea-model.gradle.kts"))
        Files.copy(repository.resolve("gradle/verify-qodana-declarations.py"), directory.resolve("gradle/verify-qodana-declarations.py"))
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true\n")
        val temporaryOwner = "working-${directory.fileName.toString().filter(Char::isLetterOrDigit)}"
        val owners = listOf("bench-one", "bench-two", temporaryOwner)
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\ninclude(\"variant-one\", \"variant-two\", \"support\", \"support-kotlin\", \"shared-inputs\", \"shared-kmp\", \"auxiliary-owner\", ${owners.joinToString { "\"$it\"" }})\n")
        val production = Files.readString(repository.resolve("build.gradle.kts")).replace("\r\n", "\n")
        val inventory = production.substringAfter("if (completeIdeaModelActive) {\n    apply(plugin = \"idea\")\n").substringBefore("\n}\n\nallprojects")
        val callback = production.substringAfter("    plugins.withId(\"me.champeau.jmh\") {").substringBefore("        val analysisJava")
        val auxiliaryCallback = production.substringAfter("    // Export configured auxiliary JVM inputs through the existing TEST analysis view.\n").substringBefore("    tasks.withType<JavaCompile>()")
        Files.writeString(
            directory.resolve("build.gradle.kts"),
            """
            import groovy.json.JsonOutput
            import java.io.StringWriter
            import javax.xml.parsers.DocumentBuilderFactory
            import javax.xml.transform.OutputKeys
            import javax.xml.transform.TransformerFactory
            import javax.xml.transform.dom.DOMSource
            import javax.xml.transform.stream.StreamResult
            import org.w3c.dom.Element
            import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
            import java.io.File
            import java.security.MessageDigest
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            import org.gradle.api.artifacts.component.ProjectComponentIdentifier
            import org.gradle.api.plugins.JavaPluginExtension
            import org.gradle.api.Project
            import org.gradle.api.tasks.SourceSet
            import org.gradle.api.tasks.SourceSetContainer
            import org.gradle.jvm.toolchain.JavaLanguageVersion
            import org.gradle.jvm.toolchain.JavaToolchainService
            import org.gradle.plugins.ide.idea.model.IdeaModel
            import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
            import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
            plugins {
                idea
                alias(libs.plugins.kotlin.jvm) apply false
                alias(libs.plugins.kotlin.multiplatform) apply false
                alias(libs.plugins.jmh) apply false
            }
            extensions.configure<IdeaModel> { project.setLanguageLevel(JavaVersion.VERSION_25) }
            val completeIdeaModelActive = true
            val minecraftTargetByProjectPath = mapOf(":variant-one" to true, ":variant-two" to true)
            $inventory
            subprojects {
                if (name == "shared-kmp") {
                    apply(plugin = "org.jetbrains.kotlin.multiplatform")
                } else {
                    apply(plugin = "org.jetbrains.kotlin.jvm")
                    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
                }
                apply(plugin = "idea")
                repositories { mavenCentral() }
                plugins.withId("me.champeau.jmh") {
            $callback
                }
            $auxiliaryCallback
            }
            """.trimIndent(),
        )
        write("support/build.gradle.kts", "")
        write("support/src/main/java/fixture/Support.java", "package fixture; public class Support { public static int value() { return 7; } }")
        write(
            "support-kotlin/build.gradle.kts",
            """
            import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
            tasks.named<KotlinJvmCompile>("compileKotlin") { compilerOptions.moduleName.set("fixture-support") }
            """.trimIndent(),
        )
        write("support-kotlin/src/main/kotlin/fixture/KotlinSupport.kt", "package fixture\npublic fun kotlinValue(): Int = 5\n")
        write("shared-inputs/kotlin/fixture/SharedValue.kt", "package fixture\ninternal fun sharedValue(): Int = 11\n")
        write("shared-inputs/kotlin/fixture/SelectedValue.kt", "package fixture\ninternal fun selectedValue(): Int = 17\n")
        write("shared-inputs/resources/shared.txt", "shared compilation resource\n")
        write(
            "shared-inputs/build.gradle.kts",
            """
            kotlin.sourceSets.named("main") {
                kotlin.setSrcDirs(listOf("kotlin"))
                kotlin.include("**/SharedValue.kt")
            }
            sourceSets.named("main") { resources.setSrcDirs(listOf("resources")) }
            """.trimIndent(),
        )
        write(
            "shared-kmp/build.gradle.kts",
            """
            plugins { alias(libs.plugins.kotlin.multiplatform) }
            kotlin {
                jvm()
                jvmToolchain(17)
                sourceSets.named("commonTest") {
                    kotlin.srcDir(rootProject.file("shared-inputs/kotlin"))
                    resources.srcDir(rootProject.file("shared-inputs/resources"))
                }
            }
            """.trimIndent(),
        )
        write("shared-kmp/src/commonMain/kotlin/fixture/OwnedValue.kt", "package fixture\npublic fun ownedValue(): Int = 13\n")
        writeAuxiliaryOwner()
        writeNativeVariants()
        owners.forEach { owner ->
            write(
                "$owner/build.gradle.kts",
                """
                plugins { alias(libs.plugins.jmh) }
                dependencies { add("jmh", project(":support")) }
                sourceSets.named("jmh") {
                    java.setSrcDirs(listOf("authored/java"))
                    resources.setSrcDirs(listOf("authored/resources"))
                }
                val additionalJmh = kotlin.sourceSets.create("additionalJmh") {
                    kotlin.setSrcDirs(listOf(rootProject.file("shared-inputs/kotlin")))
                    resources.setSrcDirs(listOf(rootProject.file("shared-inputs/resources")))
                    if (project.name == "bench-two") kotlin.include("**/SharedValue.kt")
                }
                kotlin.sourceSets.named("jmh") {
                    kotlin.setSrcDirs(listOf("authored/kotlin"))
                    dependsOn(additionalJmh)
                }
                idea.module.name = "resolved-${'$'}{project.name}"
                """.trimIndent(),
            )
            write("$owner/authored/java/fixture/JavaBenchmark.java", "package fixture; import org.openjdk.jmh.annotations.Benchmark; public class JavaBenchmark { @Benchmark public int value() { return Support.value(); } }")
            write("$owner/src/main/kotlin/fixture/InternalValue.kt", "package fixture\ninternal fun internalValue(): Int = 7\n")
            val selectedCall = if (owner == "bench-one") " + selectedValue()" else ""
            write("$owner/authored/kotlin/fixture/KotlinBenchmark.kt", "package fixture\nimport org.openjdk.jmh.annotations.Benchmark\npublic open class KotlinBenchmark { @Benchmark public fun value(): Int = internalValue() + sharedValue() + Support.value()$selectedCall }\n")
            write("$owner/authored/resources/payload.txt", "working JMH resource\n")
        }
        git("init", "-q")
        git("add", ".")
        git("-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "Create working JMH owners")
        val result = runner().build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":qodanaDeclarationInventory")?.outcome)
        val kotlinModule = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve("support-kotlin/support-kotlin.iml").toFile())
        val facets = kotlinModule.getElementsByTagName("facet")
        assertEquals(1, facets.length)
        assertEquals(
            "kotlin-language",
            facets
                .item(0)
                .attributes
                .getNamedItem("type")
                .nodeValue,
        )
        val options = kotlinModule.getElementsByTagName("option")
        val compilerOptions =
            (0 until options.length)
                .mapNotNull { index ->
                    val attributes = options.item(index).attributes
                    val name = attributes.getNamedItem("name")?.nodeValue ?: return@mapNotNull null
                    name to attributes.getNamedItem("value")?.nodeValue
                }.toMap()
        assertTrue(Files.isRegularFile(directory.resolve("support-kotlin/build/classes/kotlin/main/META-INF/fixture-support.kotlin_module")))
        assertEquals("fixture-support", compilerOptions["moduleName"])
        assertEquals("17", compilerOptions["jvmTarget"])
        val parsed = JsonSlurper().parse(directory.resolve("build/qodana/declarations.json").toFile()) as Map<*, *>
        val inventoried = parsed["owners"] as List<*>
        assertEquals(owners.map { ":$it" }.toSet(), inventoried.map { (it as Map<*, *>)["project"] }.toSet())
        inventoried.forEach { value ->
            val owner = value as Map<*, *>
            val name = (owner["project"] as String).removePrefix(":")
            assertEquals("resolved-$name", owner["module"])
            val roots = owner["roots"] as List<*>
            val existing = roots.map { it as Map<*, *> }.filter { it["exists"] == true }
            assertEquals(setOf("$name/src/main/kotlin", "$name/authored/java", "$name/authored/kotlin", "$name/authored/resources", "shared-inputs/kotlin", "shared-inputs/resources"), existing.map { it["path"] }.toSet())
            val iml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(owner["iml"] as String).toFile())
            val folders = iml.getElementsByTagName("sourceFolder")
            assertEquals(if (name == "bench-one") 5 else 4, folders.length)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:compileJmhKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:compileJmhJava")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:processJmhResources")?.outcome)
            val dependencies = owner["dependencies"] as Map<*, *>
            assertEquals(setOf("jmhCompileClasspath", "jmhRuntimeClasspath"), dependencies.keys)
            dependencies.values.forEach { closure ->
                val entries = (closure as List<*>).map { it as Map<*, *> }
                assertTrue(entries.any { it["kind"] == "Module" && it["identity"] == "support" })
                assertTrue(entries.any { (it["identity"] as String).startsWith("org.openjdk.jmh:jmh-core:") })
            }
        }
        val projects = (parsed["projects"] as List<*>).map { it as Map<*, *> }
        assertUniqueSourceOwnership(projects)
        val variantOne = projects.single { it["project"] == ":variant-one" }
        val variantTwo = projects.single { it["project"] == ":variant-two" }
        val oneModel = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(variantOne["iml"] as String).toFile())
        val twoModel = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(variantTwo["iml"] as String).toFile())
        assertEquals(2, oneModel.getElementsByTagName("sourceFolder").length)
        assertEquals(0, twoModel.getElementsByTagName("sourceFolder").length)
        assertTrue(Files.readString(directory.resolve(variantTwo["iml"] as String)).contains("variant-two/build/classes/kotlin/main/"))
        val compiledVariant = directory.resolve("variant-two/build/classes/kotlin/main")
        URLClassLoader(arrayOf(compiledVariant.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertEquals(19, loader.loadClass("fixture.NativeScreenKt").getMethod("nativeScreenValue").invoke(null))
        }
        val support = projects.single { it["project"] == ":support-kotlin" }
        assertTrue(support["outputs"] is List<*>, "Each configured JVM compilation must retain current owned output evidence.")
        val supportCompiler = support["compiler"] as Map<*, *>
        assertEquals("fixture-support", supportCompiler["moduleName"])
        assertEquals("17", supportCompiler["jvmTarget"])
        val supportRoots = (support["roots"] as List<*>).map { it as Map<*, *> }
        val supportInputs = supportRoots.single { it["path"] == "support-kotlin/src/main/kotlin" }["inputs"] as List<*>
        assertEquals(setOf("support-kotlin/src/main/kotlin/fixture/KotlinSupport.kt"), supportInputs.toSet())
        val supportOutputs = (support["outputs"] as List<*>).map { it as Map<*, *> }
        assertTrue(
            supportOutputs.any { output ->
                output["production"] == true && output["classes"] == true &&
                    (output["files"] as Map<*, *>).keys.any { it.toString().endsWith("/META-INF/fixture-support.kotlin_module") }
            },
        )
        val sharedOutputs = (projects.single { it["project"] == ":shared-kmp" }["outputs"] as List<*>).map { it as Map<*, *> }
        assertTrue(
            sharedOutputs.any { output ->
                output["production"] == true && output["classes"] == true &&
                    (output["files"] as Map<*, *>).keys.any { it.toString().endsWith("/fixture/OwnedValueKt.class") }
            },
        )
        val partialRoot = ((projects.single { it["project"] == ":shared-inputs" }["roots"] as List<*>).map { it as Map<*, *> }).single { it["path"] == "shared-inputs/kotlin" }
        assertEquals(listOf("shared-inputs/kotlin/fixture/SharedValue.kt"), partialRoot["inputs"])
        assertEquals(2, (partialRoot["files"] as Map<*, *>).size)
        val fullRoot = ((projects.single { it["project"] == ":bench-one" }["roots"] as List<*>).map { it as Map<*, *> }).single { it["path"] == "shared-inputs/kotlin" }
        assertEquals(2, (fullRoot["inputs"] as List<*>).size)
        val sharedRoots = projects.single { it["project"] == ":shared-kmp" }["roots"] as List<*>
        val roots = sharedRoots.map { it as Map<*, *> }
        assertTrue(roots.any { it["kind"] == "Source" && it["path"] == "shared-kmp/src/commonMain/kotlin" })
        assertTrue(roots.any { it["kind"] == "TestSource" && it["path"] == "shared-inputs/kotlin" })
        assertTrue(roots.any { it["kind"] == "TestResource" && it["path"] == "shared-inputs/resources" })
        assertTrue(roots.none { it["kind"] == "TestSource" && it["path"] == "shared-kmp/src/commonMain/kotlin" })
        assertEquals(TaskOutcome.SUCCESS, result.task(":shared-kmp:compileKotlinJvm")?.outcome)
        val auxiliary = (parsed["auxiliary"] as List<*>).map { it as Map<*, *> }.single()
        assertEquals(":auxiliary-owner", auxiliary["project"])
        assertEquals("verification", auxiliary["sourceSet"])
        assertEquals(TaskOutcome.SUCCESS, result.task(":auxiliary-owner:compileVerificationKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":auxiliary-owner:compileVerificationJava")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":auxiliary-owner:processVerificationResources")?.outcome)
        val auxiliaryRoots = (auxiliary["roots"] as List<*>).map { it as Map<*, *> }
        assertTrue(auxiliaryRoots.any { it["kind"] == "Source" && it["path"] == "auxiliary-owner/shared-production" })
        assertTrue(auxiliaryRoots.any { it["kind"] == "TestSource" && it["path"] == "auxiliary-owner/authored-kotlin" })
        assertTrue(auxiliaryRoots.any { it["kind"] == "TestSource" && it["path"] == "auxiliary-owner/authored-java" })
        assertTrue(auxiliaryRoots.any { it["kind"] == "TestResource" && it["path"] == "auxiliary-owner/authored-resources" })
        assertTrue(auxiliaryRoots.any { it["exists"] == false && it["path"] == "auxiliary-owner/absent/java" })
        val auxiliaryDependencies = auxiliary["dependencies"] as Map<*, *>
        auxiliaryDependencies.values.forEach { closure ->
            assertTrue((closure as List<*>).any { (it as Map<*, *>)["identity"] == "support" })
            val libraries = closure.map { it as Map<*, *> }.filter { it["kind"] == "Library" }
            assertTrue(libraries.any { (it["path"] as String).endsWith("/support-kotlin/build/classes/kotlin/main") })
            assertTrue(libraries.none { (it["path"] as String).endsWith("/support-kotlin/build/classes/java/main") || (it["path"] as String).endsWith("/support-kotlin/build/resources/main") })
        }
        val auxiliaryIml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(auxiliary["iml"] as String).toFile())
        val auxiliaryFolders = auxiliaryIml.getElementsByTagName("sourceFolder")
        val shared =
            (0 until auxiliaryFolders.length)
                .map { auxiliaryFolders.item(it) }
                .filter {
                    it.attributes
                        .getNamedItem("url")
                        .nodeValue
                        .endsWith("/shared-production")
                }
        assertEquals(1, shared.size)
        assertEquals(
            "false",
            shared
                .single()
                .attributes
                .getNamedItem("isTestSource")
                .nodeValue,
        )
        assertTrue((auxiliary["outputs"] as List<*>).any { ((it as Map<*, *>)["files"] as Map<*, *>).isNotEmpty() })
        val verifier =
            ProcessBuilder("python", "-B", repository.resolve("gradle/tests/fixtures/assert_qodana_compiler_model.py").toString(), directory.toString())
                .redirectErrorStream(true)
                .start()
        val verificationOutput = verifier.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, verifier.waitFor(), verificationOutput)
        val reused = runner().build()
        assertEquals(TaskOutcome.SUCCESS, reused.task(":qodanaDeclarationInventory")?.outcome)
        assertEquals(parsed, JsonSlurper().parse(directory.resolve("build/qodana/declarations.json").toFile()))
        val missingDependencyRunner = runner()
        val missingDependency = missingDependencyRunner.withArguments(missingDependencyRunner.arguments + "-Pfixture.missingRequiredClasspath=true").buildAndFail()
        assertTrue(missingDependency.output.contains("missing-required.jar"))
        write("auxiliary-owner/authored-kotlin/fixture/AuxiliaryValue.kt", "package fixture\npublic fun broken(): Int = missingValue\n")
        git("add", "--", "auxiliary-owner/authored-kotlin/fixture/AuxiliaryValue.kt")
        git("-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "Reject broken auxiliary compilation")
        Files.delete(directory.resolve("build/qodana/declarations.json"))
        val failed = runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":auxiliary-owner:compileVerificationKotlin")?.outcome)
        assertEquals(null, failed.task(":qodanaDeclarationInventory"))
        assertTrue(Files.exists(directory.resolve("build/qodana/declarations.json")).not())
    }

    /** Verifies physical containment and unique global ownership, including roots outside a module directory. */
    private fun assertUniqueSourceOwnership(projects: List<Map<*, *>>) {
        val owners = mutableMapOf<Path, String>()
        projects.forEach { project ->
            val model = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(project["iml"] as String).toFile())
            val folders = model.getElementsByTagName("sourceFolder")
            val moduleDirectory = directory.resolve(project["iml"] as String).parent

            fun path(url: String): Path = Path.of(url.removePrefix("file://").replace("\$PROJECT_DIR\$", directory.toString()).replace("\$MODULE_DIR\$", moduleDirectory.toString())).normalize()
            (0 until folders.length).forEach { index ->
                val folder = folders.item(index) as Element
                val source = path(folder.getAttribute("url"))
                val content = path((folder.parentNode as Element).getAttribute("url"))
                assertTrue(source.startsWith(content), "Compiler source $source must belong to its direct content root $content.")
                assertEquals(null, owners.put(source, project["module"] as String), "Compiler roots must have exactly one IDEA owner.")
            }
        }
        val benchmark = directory.resolve("bench-two/resolved-bench-two.iml")
        val contents = Files.readString(benchmark)
        assertTrue(contents.contains("bench-two/build/classes/kotlin/jmh/"))
        assertTrue(contents.contains("bench-two/build/resources/jmh/"))
        assertTrue(contents.contains("name=\"friendPaths\""))
    }

    /** Compiles the same native closure in two variants while borrowing a physically versioned source root. */
    private fun writeNativeVariants() {
        write("shared-native/kotlin/fixture/NativeValue.kt", "package fixture\ninternal fun nativeValue(): Int = 19\n")
        write("variant-two/src/main/kotlin/fixture/NativeScreen.kt", "package fixture\ninternal fun nativeScreenValue(): Int = nativeValue()\n")
        listOf("variant-one", "variant-two").forEach { variant ->
            write(
                "$variant/build.gradle.kts",
                """
                kotlin.sourceSets.named("main") {
                    kotlin.setSrcDirs(listOf(rootProject.file("shared-native/kotlin"), rootProject.file("variant-two/src/main/kotlin")))
                }
                """.trimIndent(),
            )
        }
    }

    /** Adds a custom compiler with its own real dependencies and one root already owned by production. */
    private fun writeAuxiliaryOwner() {
        write(
            "auxiliary-owner/build.gradle.kts",
            """
            val verification = sourceSets.create("verification") {
                java.setSrcDirs(listOf("authored-java", "absent/java"))
                resources.setSrcDirs(listOf("authored-resources"))
            }
            evaluationDependsOn(":support-kotlin")
            val kotlinDependency = project(":support-kotlin").extensions.getByType<SourceSetContainer>().getByName("main")
            dependencies {
                add(verification.implementationConfigurationName, project(":support"))
                add(verification.implementationConfigurationName, files(kotlinDependency.output))
                if (providers.gradleProperty("fixture.missingRequiredClasspath").isPresent) {
                    add(verification.implementationConfigurationName, files("missing-required.jar"))
                }
            }
            kotlin.sourceSets.named("main") { kotlin.srcDir("shared-production") }
            kotlin.sourceSets.named("verification") { kotlin.setSrcDirs(listOf("authored-kotlin", "shared-production")) }
            """.trimIndent(),
        )
        write("auxiliary-owner/shared-production/fixture/ProductionValue.kt", "package fixture\npublic fun productionValue(): Int = 3\n")
        write("auxiliary-owner/authored-kotlin/fixture/AuxiliaryValue.kt", "package fixture\npublic fun auxiliaryValue(): Int = Support.value() + productionValue() + kotlinValue()\n")
        write("auxiliary-owner/authored-java/fixture/JavaAuxiliary.java", "package fixture; public class JavaAuxiliary { public int value() { return Support.value(); } }")
        write("auxiliary-owner/authored-resources/payload.txt", "auxiliary compiler resource\n")
    }

    /** Writes authored fixture sources before the declaration snapshot is selected. */
    private fun write(
        relative: String,
        text: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /** Creates an unsigned fixture repository without invoking workstation signing. */
    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    /** Bounds the real plugin/model fixture without loading Minecraft or benchmark harness generation. */
    private fun runner(): GradleRunner =
        GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
            "cleanIdea",
            "idea",
            "qodanaKotlinModel",
            ":shared-kmp:compileKotlinJvm",
            "--no-configure-on-demand",
            "--max-workers=1",
            "--no-parallel",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
            "--stacktrace",
        )
}
