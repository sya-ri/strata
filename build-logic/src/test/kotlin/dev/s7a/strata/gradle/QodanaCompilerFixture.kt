package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds working JVM, shared, auxiliary and benchmark owners around the production compiler exporters.
 */
internal class QodanaCompilerFixture(
    private val directory: Path,
    private val repository: Path,
) {
    val owners = listOf("bench-one", "bench-two", "working-${directory.fileName.toString().filter(Char::isLetterOrDigit)}")

    /**
     * Writes the selected sources and starts a repository so every export has an actual revision.
     */
    fun prepare() {
        Files.createDirectories(directory.resolve("gradle"))
        listOf("libs.versions.toml", "qodana-idea-model.gradle.kts", "verify-qodana-declarations.py", "prepare-qodana-project.py").forEach { name ->
            Files.copy(repository.resolve("gradle/$name"), directory.resolve("gradle/$name"))
        }
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true\n")
        write("settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\"variant-one\", \"variant-two\", \"support\", \"support-kotlin\", \"shared-inputs\", \"shared-kmp\", \"common-support\", \"auxiliary-owner\", \"example-consumer\", ${owners.joinToString { "\"$it\"" }})\n")
        writeRootBuild()
        writeSupport()
        writeSharedInputs()
        writeSharedProject()
        writeCommonSupport()
        writeSharedSources()
        writeAuxiliaryOwner()
        writeExampleConsumer()
        writeNativeVariants()
        owners.forEach(::writeBenchmark)
        git("init", "-q")
    }

    /**
     * Captures a fixture change without using the workstation signing identity.
     */
    fun commit(
        message: String,
        vararg paths: String,
    ) {
        git("add", "--", *paths)
        git("-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", message)
    }

    /**
     * Inserts current production callbacks into the working Gradle model.
     */
    private fun writeRootBuild() {
        val production = Files.readString(repository.resolve("build.gradle.kts")).replace("\r\n", "\n")
        val inventory = production.substringAfter("if (completeIdeaModelActive) {\n    apply(plugin = \"idea\")\n").substringBefore("\n}\n\nallprojects")
        val callback = production.substringAfter("    plugins.withId(\"me.champeau.jmh\") {").substringBefore("        val analysisJava")
        val auxiliaryCallback = production.substringAfter("    // Export configured auxiliary JVM inputs through the existing TEST analysis view.\n").substringBefore("    tasks.withType<JavaCompile>()")
        val multiplatformCallback = production.substringAfter("        if (completeIdeaModelActive && hasJvmTarget) {\n").substringBefore("        tasks.matching { it.name in setOf(\"buildWeb\"").substringBeforeLast("        }")
        val template = requireNotNull(javaClass.getResource("/qodana-compiler/build.gradle.kts")).readText()
        write(
            "build.gradle.kts",
            template
                .replace("__INVENTORY__", inventory)
                .replace("__MULTIPLATFORM_CALLBACK__", multiplatformCallback)
                .replace("__JMH_CALLBACK__", callback)
                .replace("__AUXILIARY_CALLBACK__", auxiliaryCallback),
        )
    }

    /**
     * Provides real Java and Kotlin dependency outputs with distinct compiler settings.
     */
    private fun writeSupport() {
        write("support/build.gradle.kts", "")
        write("support/src/main/java/fixture/Support.java", "package fixture; public class Support { public static int value() { return 7; } }")
        write(
            "support-kotlin/build.gradle.kts",
            """
            import org.gradle.api.tasks.compile.JavaCompile
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget
            import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
            tasks.withType<JavaCompile>().configureEach {
                sourceCompatibility = "11"
                targetCompatibility = "11"
                options.release.set(11)
            }
            tasks.named<KotlinJvmCompile>("compileKotlin") {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
                compilerOptions.moduleName.set("fixture-support")
                compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
            }
            tasks.named<KotlinJvmCompile>("compileTestKotlin") { compilerOptions.jvmTarget.set(JvmTarget.JVM_11) }
            """.trimIndent(),
        )
        write("support-kotlin/src/main/kotlin/fixture/KotlinSupport.kt", "package fixture\npublic fun kotlinValue(): Int = 5\n")
        write("support-kotlin/src/test/kotlin/fixture/Support.kt", "package fixture\ninternal class Support { companion object { fun value(): Int = 99 } }\n")
    }

    /**
     * Provides a shared directory whose compiler selection differs between owners.
     */
    private fun writeSharedInputs() {
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
    }

    /**
     * Configures actual JVM, JS and metadata compilations with shared refinement roots.
     */
    private fun writeSharedProject() {
        write(
            "shared-kmp/build.gradle.kts",
            """
            import org.gradle.jvm.toolchain.JavaToolchainService
            plugins { alias(libs.plugins.kotlin.multiplatform) }
            kotlin {
                jvm()
                js { nodejs() }
                jvmToolchain(17)
                explicitApi()
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
                val sharedBaseMain = sourceSets.create("sharedBaseMain") {
                    dependsOn(sourceSets.getByName("commonMain"))
                }
                sourceSets.named("jvmMain") { dependsOn(sharedBaseMain) }
                sourceSets.named("commonTest") {
                    kotlin.srcDir(rootProject.file("shared-inputs/kotlin"))
                    resources.srcDir(rootProject.file("shared-inputs/resources"))
                }
                sourceSets.named("commonMain") { dependencies { implementation(project(":common-support")) } }
            }
            val jvmMain = extensions.getByType<SourceSetContainer>().getByName("jvmMain")
            dependencies { add(jvmMain.runtimeOnlyConfigurationName, project(":support")) }
            val main = kotlin.targets.getByName("jvm").compilations.getByName("main")
            tasks.register<JavaExec>("verifyFixture") {
                dependsOn(main.compileTaskProvider)
                javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
                classpath(main.output.allOutputs, requireNotNull(main.runtimeDependencyFiles))
                mainClass.set("fixture.JvmValueKt")
            }
            """.trimIndent(),
        )
    }

    /**
     * Exercises transitive common metadata and platform-specific library selection.
     */
    private fun writeCommonSupport() {
        write(
            "common-support/build.gradle.kts",
            """
            plugins { alias(libs.plugins.kotlin.multiplatform) }
            kotlin {
                jvm()
                js { nodejs() }
                jvmToolchain(17)
                explicitApi()
                sourceSets.named("commonMain") { dependencies { implementation(libs.kotlinx.coroutines.core) } }
            }
            """.trimIndent(),
        )
        write(
            "common-support/src/commonMain/kotlin/fixture/CommonSupport.kt",
            """
            package fixture

            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlin.jvm.JvmInline
            import kotlin.jvm.JvmSynthetic
            import kotlin.math.ceil
            import kotlin.math.floor
            import kotlin.properties.ReadOnlyProperty

            /** Exercises shared [Pair], [AutoCloseable], [ReadOnlyProperty] and coroutine metadata. */
            @JvmSynthetic
            public fun commonSupport(): Pair<Int, Int> {
                val resource = AutoCloseable {}
                resource.close()
                val number by ReadOnlyProperty<Any?, Int> { _, _ -> MutableStateFlow(12).value }
                return Pair(FixtureId(number).value, (ceil(1.5) - floor(1.5)).toInt())
            }

            @JvmInline
            public value class FixtureId(public val value: Int)
            """.trimIndent(),
        )
    }

    /**
     * Compiles expect/actual declarations and executes the retained JVM result.
     */
    private fun writeSharedSources() {
        write("shared-kmp/src/commonMain/kotlin/fixture/OwnedValue.kt", "package fixture\npublic fun ownedValue(): Int = commonSupport().let { (first, second) -> first + second }\n")
        write("shared-kmp/src/sharedBaseMain/kotlin/fixture/BaseValue.kt", "package fixture\npublic fun baseValue(): Int = ownedValue()\n")
        write("shared-kmp/src/commonMain/kotlin/fixture/FixtureContext.kt", "package fixture\ninternal expect class FixtureContext<T : Any>() { var current: T? }\n")
        write("shared-kmp/src/jvmMain/kotlin/fixture/FixtureContext.kt", "package fixture\ninternal actual class FixtureContext<T : Any> actual constructor() { private val storage = ThreadLocal<T>(); actual var current: T? get() = storage.get(); set(value) { if (value == null) storage.remove() else storage.set(value) } }\n")
        write("shared-kmp/src/jsMain/kotlin/fixture/FixtureContext.kt", "package fixture\ninternal actual class FixtureContext<T : Any> actual constructor() { actual var current: T? = null }\n")
        write("shared-kmp/src/jvmMain/kotlin/fixture/JvmValue.kt", "package fixture\npublic fun jvmValue(): Int { val context = FixtureContext<Int>(); context.current = baseValue(); val value = checkNotNull(context.current); context.current = null; check(context.current == null); return value }\npublic fun main() { check(jvmValue() == 13) }\n")
    }

    /**
     * Writes benchmark owners with independently selected shared inputs.
     */
    private fun writeBenchmark(owner: String) {
        write(
            "$owner/build.gradle.kts",
            $$"""
            plugins { alias(libs.plugins.jmh) }
            dependencies { add("jmh", project(":support")) }
            sourceSets.named("jmh") {
                java.setSrcDirs(listOf("authored/java"))
                resources.setSrcDirs(listOf("authored/resources"))
            }
            val additionalJmh = kotlin.sourceSets.create("additionalJmh") {
                kotlin.setSrcDirs(listOf(rootProject.file("shared-inputs/kotlin")))
                resources.setSrcDirs(listOf(rootProject.file("shared-inputs/resources")))
                $${mapOf("bench-two" to "kotlin.include(\"**/SharedValue.kt\")").getOrDefault(owner, "")}
            }
            kotlin.sourceSets.named("jmh") {
                kotlin.setSrcDirs(listOf("authored/kotlin"))
                dependsOn(additionalJmh)
            }
            idea.module.name = "resolved-${project.name}"
            """.trimIndent(),
        )
        write("$owner/authored/java/fixture/JavaBenchmark.java", "package fixture; import org.openjdk.jmh.annotations.Benchmark; public class JavaBenchmark { @Benchmark public int value() { return Support.value(); } }")
        write("$owner/src/main/kotlin/fixture/InternalValue.kt", "package fixture\ninternal fun internalValue(): Int = 7\n")
        val selectedCall = mapOf("bench-one" to " + selectedValue()").getOrDefault(owner, "")
        write("$owner/authored/kotlin/fixture/KotlinBenchmark.kt", "package fixture\nimport org.openjdk.jmh.annotations.Benchmark\npublic open class KotlinBenchmark { @Benchmark public fun value(): Int = internalValue() + sharedValue() + Support.value()$selectedCall }\n")
        write("$owner/authored/resources/payload.txt", "working JMH resource\n")
    }

    /**
     * Compiles the same native closure in two variants while borrowing a physically versioned source root.
     */
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
                dependencies { implementation(project(":support-kotlin")) }
                """.trimIndent(),
            )
        }
    }

    /**
     * Adds a custom compiler with its own real dependencies and one root already owned by production.
     */
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
                implementation(project(":support"))
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
        write("auxiliary-owner/shared-production/fixture/ProductionValue.kt", "package fixture\npublic fun productionValue(): Int = Support.value()\n")
        write("auxiliary-owner/authored-kotlin/fixture/AuxiliaryValue.kt", "package fixture\npublic fun auxiliaryValue(): Int = Support.value() + productionValue() + kotlinValue()\n")
        write("auxiliary-owner/authored-java/fixture/JavaAuxiliary.java", "package fixture; public class JavaAuxiliary { public int value() { return Support.value(); } }")
        write("auxiliary-owner/authored-resources/payload.txt", "auxiliary compiler resource\n")
    }

    /**
     * Compiles an API-only example once and consumes its internal declarations from production.
     */
    private fun writeExampleConsumer() {
        write(
            "example-consumer/build.gradle.kts",
            """
            val examples = sourceSets.create("examples")
            dependencies {
                add(examples.compileOnlyConfigurationName, project(":support-kotlin"))
                implementation(examples.output)
            }
            kotlin.target.compilations.named("main") {
                associateWith(kotlin.target.compilations.getByName(examples.name))
            }
            """.trimIndent(),
        )
        write("example-consumer/src/examples/kotlin/fixture/ExampleValue.kt", "package fixture\ninternal fun exampleValue(): Int = kotlinValue()\n")
        write("example-consumer/src/main/kotlin/fixture/ExampleConsumer.kt", "package fixture\ninternal fun consumedExample(): Int = exampleValue()\n")
    }

    /**
     * Writes authored fixture sources before the declaration snapshot is selected.
     */
    fun write(
        relative: String,
        text: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /**
     * Creates an unsigned fixture repository without invoking workstation signing.
     */
    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    /**
     * Bounds the real plugin/model fixture without loading Minecraft or benchmark harness generation.
     */
    fun runner(): GradleRunner =
        GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
            "cleanIdea",
            "idea",
            "qodanaKotlinModel",
            ":shared-kmp:verifyFixture",
            ":shared-kmp:compileCommonMainKotlinMetadata",
            ":shared-kmp:compileKotlinJs",
            "--no-configure-on-demand",
            "--max-workers=2",
            "--parallel",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
            "--stacktrace",
        )
}
