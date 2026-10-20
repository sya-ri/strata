package dev.s7a.strata.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.w3c.dom.Element
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks fixture-specific compiler evidence independently of the production declaration verifier.
 */
internal class QodanaCompilerModelAssertions(
    private val directory: Path,
    private val repository: Path,
) {
    private val inventory = JsonSlurper().parse(directory.resolve("build/qodana/declarations.json").toFile()) as Map<*, *>
    private val projects = objects(inventory["projects"]).indexed("project")
    private val support get() = projects.getValue(":support-kotlin")

    /**
     * Verifies actual compiler outcomes, exact selections and dependency ownership for the fixture revision.
     */
    fun verify(
        result: BuildResult,
        owners: List<String>,
    ): Map<*, *> {
        assertSupportFacet()
        assertOwners(result, owners)
        QodanaSourceContextAssertions(directory).verify(projects.values)
        assertNativeVariants()
        assertCompilerOptions()
        assertCommonSources()
        assertProductionDependencies()
        assertCompiledOutputs()
        assertSelectedRoots(result)
        assertAuxiliary(result)
        assertAuxiliaryModel()
        assertCompilerModel()
        return inventory
    }

    /**
     * Preserves the Kotlin facet and compiler module identity in the imported library.
     */
    private fun assertSupportFacet() {
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
        assertEquals("11", compilerOptions["jvmTarget"])
        assertEquals("strict", compilerOptions["explicitApi"])
    }

    /**
     * Requires every working benchmark owner and its actual source and dependency closure.
     */
    private fun assertOwners(
        result: BuildResult,
        owners: List<String>,
    ) {
        val inventoried = inventory["owners"] as List<*>
        assertEquals(owners.map { ":$it" }.toSet(), inventoried.map { (it as Map<*, *>)["project"] }.toSet())
        inventoried.forEach { value ->
            val owner = value as Map<*, *>
            val name = (owner["project"] as String).removePrefix(":")
            assertEquals("resolved-$name", owner["module"])
            val roots = owner["roots"] as List<*>
            val existing = roots.map { it as Map<*, *> }.filter { it["exists"] == true }
            assertEquals(setOf("$name/src/main/kotlin", "$name/authored/java", "$name/authored/kotlin", "$name/authored/resources", "shared-inputs/kotlin", "shared-inputs/resources"), existing.map { it["path"] }.toSet())
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:compileJmhKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:compileJmhJava")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:processJmhResources")?.outcome)
            val dependencies = owner["dependencies"] as Map<*, *>
            assertEquals(setOf("jmhCompileClasspath", "jmhRuntimeClasspath"), dependencies.keys)
            dependencies.values.forEach { closure ->
                val entries = (closure as List<*>).map { it as Map<*, *> }
                assertTrue("support" in entries.filter { kind(it) == DependencyKind.Module }.map { it["identity"] })
                assertTrue(entries.any { (it["identity"] as String).startsWith("org.openjdk.jmh:jmh-core:") })
            }
        }
    }

    /**
     * Executes the borrowed variant compilation and checks its physical root ownership.
     */
    private fun assertNativeVariants() {
        val variantOne = projects.getValue(":variant-one")
        val variantTwo = projects.getValue(":variant-two")
        val oneModel = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(variantOne["iml"] as String).toFile())
        val twoModel = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(variantTwo["iml"] as String).toFile())
        assertEquals(2, oneModel.getElementsByTagName("sourceFolder").length)
        assertEquals(2, twoModel.getElementsByTagName("sourceFolder").length)
        val compiledVariant = directory.resolve("variant-two/build/classes/kotlin/main")
        URLClassLoader(arrayOf(compiledVariant.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertEquals(19, loader.loadClass("fixture.NativeScreenKt").getMethod("nativeScreenValue").invoke(null))
        }
    }

    /**
     * Retains the configured toolchain and independent compiler options for every owner.
     */
    private fun assertCompilerOptions() {
        assertTrue(support["outputs"] is List<*>, "Each configured JVM compilation must retain current owned output evidence.")
        val supportCompiler = support["compiler"] as Map<*, *>
        val productionCompilation = (projects.getValue(":auxiliary-owner")["productionCompilations"] as List<*>).single() as Map<*, *>
        assertEquals("main", productionCompilation["sourceSet"])
        (productionCompilation["dependencies"] as Map<*, *>).values.forEach { closure ->
            assertTrue("support" in objects(closure).map { it["identity"] })
        }
        assertEquals("fixture-support", supportCompiler["moduleName"])
        assertEquals("11", supportCompiler["jvmTarget"])
        assertEquals(17, (support["compilerToolchain"] as Map<*, *>)["java"])
        assertEquals("strict", supportCompiler["explicitApi"])
        val sharedCompiler = projects.getValue(":shared-kmp")["compiler"] as Map<*, *>
        assertEquals("strict", sharedCompiler["explicitApi"])
        assertEquals(true, sharedCompiler["expectActualClasses"])
    }

    /**
     * Verifies common refinements, exact input files and transitive common dependencies.
     */
    private fun assertCommonSources() {
        val sharedProject = projects.getValue(":shared-kmp")
        assertEquals("jvmMain", sharedProject["defaultSourceSet"])
        assertEquals(setOf("commonMain", "sharedBaseMain"), (sharedProject["sourceSetDependencies"] as List<*>).toSet())
        val commonSources = objects(sharedProject["commonSourceSets"]).indexed("name")
        assertEquals(setOf("commonMain", "sharedBaseMain"), commonSources.keys)
        val commonMain = commonSources.getValue("commonMain")
        val commonModel = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve("shared-kmp/shared-kmp.commonMain.iml").toFile())
        val commonEdges = commonModel.getElementsByTagName("orderEntry")
        val dependencyScopes =
            (0 until commonEdges.length)
                .map { commonEdges.item(it) as Element }
                .filter { it.hasAttribute("module-name") }
                .associate { it.getAttribute("module-name") to it.getAttribute("scope").ifEmpty { "COMPILE" } }
        assertEquals("COMPILE", dependencyScopes["common-support"])
        assertEquals("RUNTIME", dependencyScopes["support"])
        assertEquals("JVM 17", (commonModel.getElementsByTagName("configuration").item(0) as Element).getAttribute("platform"))
        val commonSupport = (projects.getValue(":common-support")["productionCompilations"] as List<*>).single() as Map<*, *>
        val commonSupportDependencies = (commonSupport["dependencies"] as Map<*, *>).values.flatMap { it as List<*> }.map { it as Map<*, *> }
        assertTrue(commonSupportDependencies.any { (it["identity"] as String).startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:") })
        assertEquals(emptyList<String>(), commonMain["dependsOn"])
        assertEquals(listOf("shared-kmp/src/commonMain/kotlin"), commonMain["kotlinRoots"])
        assertEquals(listOf("shared-kmp/src/commonMain/kotlin/fixture/FixtureContext.kt", "shared-kmp/src/commonMain/kotlin/fixture/OwnedValue.kt"), commonMain["kotlinInputs"])
        val sharedBase = commonSources.getValue("sharedBaseMain")
        assertEquals(listOf("commonMain"), sharedBase["dependsOn"])
        assertEquals(listOf("shared-kmp/src/sharedBaseMain/kotlin/fixture/BaseValue.kt"), sharedBase["kotlinInputs"])
        assertEquals(emptyList<Any>(), support["commonSourceSets"])
    }

    /**
     * Keeps runtime-only dependencies outside the compile classpath.
     */
    private fun assertProductionDependencies() {
        val sharedProduction = (projects.getValue(":shared-kmp")["productionCompilations"] as List<*>).single() as Map<*, *>
        val sharedClasspaths = sharedProduction["classpaths"] as Map<*, *>
        assertEquals("jvmCompileClasspath", sharedClasspaths["compile"])
        assertEquals("jvmRuntimeClasspath", sharedClasspaths["runtime"])
        val sharedDependencies = sharedProduction["dependencies"] as Map<*, *>
        val compileDependencies = (sharedDependencies[sharedClasspaths["compile"]] as List<*>).map { it as Map<*, *> }
        val runtimeDependencies = (sharedDependencies[sharedClasspaths["runtime"]] as List<*>).map { it as Map<*, *> }
        assertTrue("support" !in compileDependencies.map { it["identity"] })
        assertTrue("support" in runtimeDependencies.map { it["identity"] })
    }

    /**
     * Requires current compiled class and Kotlin metadata evidence.
     */
    private fun assertCompiledOutputs() {
        val supportRoots = (support["roots"] as List<*>).map { it as Map<*, *> }
        val supportInputs = root(supportRoots, "support-kotlin/src/main/kotlin")["inputs"] as List<*>
        assertEquals(setOf("support-kotlin/src/main/kotlin/fixture/KotlinSupport.kt"), supportInputs.toSet())
        val supportOutputs = (support["outputs"] as List<*>).map { it as Map<*, *> }
        assertTrue(
            supportOutputs.any { output ->
                output["production"] == true && output["classes"] == true &&
                    (output["files"] as Map<*, *>).keys.any { it.toString().endsWith("/META-INF/fixture-support.kotlin_module") }
            },
        )
        val sharedOutputs = (projects.getValue(":shared-kmp")["outputs"] as List<*>).map { it as Map<*, *> }
        assertTrue(
            sharedOutputs.any { output ->
                output["production"] == true && output["classes"] == true &&
                    (output["files"] as Map<*, *>).keys.any { it.toString().endsWith("/fixture/OwnedValueKt.class") }
            },
        )
    }

    /**
     * Retains different selections of a shared directory without changing source roles.
     */
    private fun assertSelectedRoots(result: BuildResult) {
        val partialRoot = root(objects(projects.getValue(":shared-inputs")["roots"]), "shared-inputs/kotlin")
        assertEquals(listOf("shared-inputs/kotlin/fixture/SharedValue.kt"), partialRoot["inputs"])
        assertEquals(2, (partialRoot["files"] as Map<*, *>).size)
        val fullRoot = root(objects(projects.getValue(":bench-one")["roots"]), "shared-inputs/kotlin")
        assertEquals(2, (fullRoot["inputs"] as List<*>).size)
        val sharedRoots = projects.getValue(":shared-kmp")["roots"] as List<*>
        val roots = sharedRoots.map { it as Map<*, *> }
        assertEquals("Source", root(roots, "shared-kmp/src/commonMain/kotlin")["kind"])
        assertEquals("TestSource", root(roots, "shared-inputs/kotlin")["kind"])
        assertEquals("TestResource", root(roots, "shared-inputs/resources")["kind"])
        assertEquals(TaskOutcome.SUCCESS, result.task(":shared-kmp:compileKotlinJvm")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":shared-kmp:verifyFixture")?.outcome)
    }

    /**
     * Requires the real auxiliary compiler outputs and complete dependency closure.
     */
    private fun assertAuxiliary(result: BuildResult) {
        val auxiliary = (inventory["auxiliary"] as List<*>).map { it as Map<*, *> }.single()
        assertEquals(":auxiliary-owner", auxiliary["project"])
        assertEquals("verification", auxiliary["sourceSet"])
        assertEquals(TaskOutcome.SUCCESS, result.task(":auxiliary-owner:compileVerificationKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":auxiliary-owner:compileVerificationJava")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":auxiliary-owner:processVerificationResources")?.outcome)
        val auxiliaryRoots = (auxiliary["roots"] as List<*>).map { it as Map<*, *> }
        assertEquals("Source", root(auxiliaryRoots, "auxiliary-owner/shared-production")["kind"])
        assertEquals("TestSource", root(auxiliaryRoots, "auxiliary-owner/authored-kotlin")["kind"])
        assertEquals("TestSource", root(auxiliaryRoots, "auxiliary-owner/authored-java")["kind"])
        assertEquals("TestResource", root(auxiliaryRoots, "auxiliary-owner/authored-resources")["kind"])
        assertEquals(false, root(auxiliaryRoots, "auxiliary-owner/absent/java")["exists"])
        val auxiliaryDependencies = auxiliary["dependencies"] as Map<*, *>
        auxiliaryDependencies.values.forEach { closure ->
            assertTrue("support" in objects(closure).map { it["identity"] })
            val libraries = objects(closure).filter { kind(it) == DependencyKind.Library }
            assertTrue(libraries.any { (it["path"] as String).endsWith("/support-kotlin/build/classes/kotlin/main") })
            assertTrue(libraries.none { (it["path"] as String).endsWith("/support-kotlin/build/classes/java/main") || (it["path"] as String).endsWith("/support-kotlin/build/resources/main") })
        }
    }

    /**
     * Keeps a root shared with production in its original analysis role.
     */
    private fun assertAuxiliaryModel() {
        val auxiliary = objects(inventory["auxiliary"]).single()
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
    }

    /**
     * Checks fresh exports independently, including SDK, classpath and ownership rejection controls.
     */
    fun assertCompilerModel() {
        val verifier =
            ProcessBuilder("python", "-B", repository.resolve("gradle/tests/fixtures/assert_qodana_compiler_model.py").toString(), directory.toString())
                .redirectErrorStream(true)
                .start()
        val verificationOutput = verifier.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, verifier.waitFor(), verificationOutput)
    }

    /**
     * Reads the required compiled Kotlin library fingerprint from the current declaration export.
     */
    fun supportOutputHash(): String {
        val auxiliary = (inventory["auxiliary"] as List<*>).single() as Map<*, *>
        val dependencies = auxiliary["dependencies"] as Map<*, *>
        return (dependencies.values.first() as List<*>)
            .map { it as Map<*, *> }
            .single { kind(it) == DependencyKind.Library && (it["path"] as String).endsWith("/support-kotlin/build/classes/kotlin/main") }["sha256"] as String
    }

    /**
     * Reads exported objects without sharing the production parser's validation behavior.
     */
    private fun objects(value: Any?): List<Map<*, *>> = (value as List<*>).map { it as Map<*, *> }

    /**
     * Indexes opaque exported identities and rejects duplicate declarations.
     */
    private fun List<Map<*, *>>.indexed(key: String): Map<String, Map<*, *>> {
        val indexed = associateBy { it[key] as String }
        assertEquals(size, indexed.size, "Each $key must identify exactly one declaration.")
        return indexed
    }

    /**
     * Selects one declared physical root without weakening uniqueness checks.
     */
    private fun root(
        roots: List<Map<*, *>>,
        path: String,
    ): Map<*, *> = roots.groupBy { it["path"] }.getValue(path).single()

    /**
     * Decodes the finite dependency role at the exported JSON boundary.
     */
    private fun kind(dependency: Map<*, *>): DependencyKind = DependencyKind.valueOf(dependency["kind"] as String)

    /**
     * Distinguishes module dependencies from compiled external or project-output libraries.
     */
    private enum class DependencyKind {
        Module,
        Library,
    }
}
