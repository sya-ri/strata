package dev.s7a.strata.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true\n")
        val temporaryOwner = "working-${directory.fileName.toString().filter(Char::isLetterOrDigit)}"
        val owners = listOf("bench-one", "bench-two", temporaryOwner)
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\ninclude(\"support\", \"support-kotlin\", \"shared-kmp\", \"auxiliary-owner\", ${owners.joinToString { "\"$it\"" }})\n")
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
            val completeIdeaModelActive = true
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
        write("shared-inputs/resources/shared.txt", "shared compilation resource\n")
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
            write("$owner/authored/kotlin/fixture/KotlinBenchmark.kt", "package fixture\nimport org.openjdk.jmh.annotations.Benchmark\npublic open class KotlinBenchmark { @Benchmark public fun value(): Int = internalValue() + sharedValue() + Support.value() }\n")
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
            (0 until options.length).associate { index ->
                val attributes = options.item(index).attributes
                attributes.getNamedItem("name").nodeValue to attributes.getNamedItem("value").nodeValue
            }
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
            assertEquals(6, folders.length)
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
