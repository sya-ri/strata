import me.champeau.jmh.JmhBytecodeGeneratorTask
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Properties

plugins {
    alias(libs.plugins.jmh)
}

extensions.configure<DetektExtension> { source.from("src/jmh/kotlin") }

dependencies {
    implementation(project(":performance-testkit"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    add("jmh", project(":api"))
    add("jmh", project(":performance-testkit"))
    add("jmh", project(":runtime:core"))
    add("jmh", project(":runtime:headless"))
    add("jmh", project(":runtime:minecraft"))
    add("jmh", project(":runtime:minecraft-fonts-lwjgl"))
}

// The separate component corpus uses one isolated native generation, matching the shipped modern showcase.
val componentCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val componentLwjglVersion = componentCatalog.findVersion("lwjgl-minecraft-263").orElseThrow().requiredVersion
val componentNativeClassifier = providers.gradleProperty("strata.fontNatives").orElse(providers.provider {
    val platform = when {
        System.getProperty("os.name").startsWith("Windows") -> "windows"
        System.getProperty("os.name").startsWith("Mac") -> "macos"
        System.getProperty("os.name").startsWith("Linux") -> "linux"
        else -> error("Set strata.fontNatives for this operating system")
    }
    val suffix = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> ""
        "aarch64", "arm64" -> "-arm64"
        "x86", "i386" -> "-x86"
        "arm", "arm32", "armv7l" -> "-arm32"
        else -> error("Set strata.fontNatives for this architecture")
    }
    "natives-$platform$suffix"
}).get()
require(componentNativeClassifier.matches(Regex("natives-[a-z0-9-]+")))
dependencies {
    add("jmhRuntimeOnly", "com.ibm.icu:icu4j:${componentCatalog.findVersion("icu-minecraft-262").orElseThrow().requiredVersion}")
    listOf("lwjgl", "lwjgl-stb", "lwjgl-freetype").forEach { binding ->
        add("jmhRuntimeOnly", "org.lwjgl:$binding:$componentLwjglVersion")
        add("jmhRuntimeOnly", "org.lwjgl:$binding:$componentLwjglVersion:$componentNativeClassifier")
    }
}

val componentLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.java.minecraft.get().toInt()))
}

val showcaseSources = objects.sourceDirectorySet("performanceShowcase", "Shipped shared API and JVM documentation examples").apply {
    srcDir(rootProject.file("integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin"))
    srcDir(rootProject.file("integration/docs/src/main/kotlin"))
    include("**/*Example.kt")
    // These complete native screens are not used by the portable component definitions.
    exclude("**/MinecraftInventoryExample.kt", "**/MinecraftSocialExample.kt")
}
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("jmh") { kotlin.source(showcaseSources) }
}

jmh {
    jmhVersion.set(libs.versions.benchmark.harness)
    includes.set(listOf("dev\\.s7a\\.strata\\.quality\\.benchmark\\.ComponentRenderingBenchmark.*"))
}

val verifyComponentRenderingWork = tasks.register<JavaExec>("verifyComponentRenderingWork") {
    group = "verification"
    description = "Exercises every shipped public component through a real Minecraft-profile host and the shared work assertions."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.ComponentWorkEvidence")
    systemProperty("strata.performance.fontFixture", rootProject.file("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf").absolutePath)
    javaLauncher.set(componentLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("check") { dependsOn(verifyComponentRenderingWork) }

tasks.register<JavaExec>("captureComponentInventory") {
    group = "verification"
    description = "Stages exact component API performance assignments for review; never updates the verification baseline."
    dependsOn("jmhClasses")
    classpath = sourceSets.named("jmh").get().runtimeClasspath
    mainClass.set("dev.s7a.strata.quality.benchmark.ComponentInventoryEvidence")
    args(layout.buildDirectory.file("performance/component-api.tsv").get().asFile.absolutePath)
}

tasks.register<JavaExec>("jmhComponents") {
    group = "verification"
    description = "Runs the independent component corpus with JMH; the historical suite has a separate dependency graph."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.ComponentPerformanceEvidence")
    javaLauncher.set(componentLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val repetition = providers.gradleProperty("strata.performance.repetition").map(String::toInt).getOrElse(0)
    require(0 <= repetition)
    val quick = providers.gradleProperty("strata.performance.quick").map(String::toBooleanStrict).getOrElse(false)
    val smoke = providers.gradleProperty("strata.performance.smoke").map(String::toBooleanStrict).getOrElse(false)
    val short = quick || smoke
    val mode = providers.gradleProperty("strata.performance.mode").getOrElse("avgt")
    require(mode in setOf("avgt", "sample"))
    val benchmarks = providers.gradleProperty("strata.performance.benchmarks").orNull
    val parameters = providers.gradleProperty("strata.performance.parameters").orNull
    val fixtureInputs = providers.gradleProperty("strata.performance.fixtureInputs").orNull
    require(listOf("fonts", "stress", "exceptionalText", "portableText").none { providers.gradleProperty("strata.performance.$it").isPresent }) { "Select generated fixture classes with strata.performance.benchmarks" }
    require(benchmarks == null || smoke.not()) { "Smoke and explicit fixture selection are separate scopes" }
    require(parameters == null || benchmarks != null) { "Compiled parameter selection requires explicit fixtures" }
    val workloads = providers.gradleProperty("strata.performance.workloads").orNull
    val corpus = providers.gradleProperty("strata.performance.suite").getOrElse(if (benchmarks != null) "selected" else if (workloads != null) "components-selected" else "components")
    require(corpus.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid performance output suite name" }
    val suite = (if (quick) "$corpus-quick" else if (smoke) "$corpus-smoke" else corpus) + (if (mode in setOf("sample")) "-sample" else "")
    workloads?.let { systemProperty("strata.performance.workloads", it) }
    benchmarks?.let { systemProperty("strata.performance.benchmarks", it) }
    parameters?.let { systemProperty("strata.performance.parameters", rootProject.file(it).absolutePath) }
    fixtureInputs?.let { systemProperty("strata.performance.fixtureInputs", rootProject.file(it).absolutePath) }
    val result = providers.gradleProperty("strata.performance.output").map { rootProject.file(it) }.orElse(layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition").map { it.asFile })
    args(result.get().absolutePath, repetition.toString(), if (benchmarks != null) ".*" else "ComponentRenderingBenchmark.*", "-bm", mode, "-wi", if (short) "0" else "3", "-w", "1s", "-i", if (short) "1" else "5", "-r", if (short) "100ms" else "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc", "-jvmArgsAppend", "--enable-native-access=ALL-UNNAMED")
    systemProperty("strata.performance.fontFixture", rootProject.file("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf").absolutePath)
    systemProperty("strata.performance.smoke", smoke || (quick && workloads == null && benchmarks == null))
    systemProperty("strata.performance.mode", mode)
    val inputsManifest = layout.buildDirectory.file("performance/control-inputs.properties")
    doFirst {
        val entries = Properties()
        configurations.getByName("jmhRuntimeClasspath").incoming.artifacts.artifacts.forEach { artifact ->
            val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            if (module != null) {
                val label = "${module.group}:${module.module}:${module.version}:${artifact.file.name}"
                require(entries.setProperty(label, artifact.file.absolutePath) == null) { "Duplicate resolved control library: $label" }
            }
        }
        require(entries.isNotEmpty()) { "The JMH control library inventory is missing" }
        val manifest = inputsManifest.get().asFile
        manifest.parentFile.mkdirs()
        manifest.bufferedWriter(Charsets.UTF_8).use { entries.store(it, "Resolved non-Strata control libraries") }
        systemProperty("strata.performance.inputs", manifest.absolutePath)
    }
}

val changedPathFile = providers.gradleProperty("strata.performance.changedPaths").map { rootProject.file(it).absolutePath }
tasks.withType<JavaExec>().matching { it.name in setOf("verifyComponentRenderingWork", "jmhComponents") }.configureEach {
    changedPathFile.orNull?.let { systemProperty("strata.performance.changedPaths", it) }
}

val verifyStressRenderingWork = tasks.register<JavaExec>("verifyStressRenderingWork") {
    group = "verification"
    description = "Checks real stress edits, navigation, animation, multipixel tiling and terminal lifetimes without time thresholds."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.StressWorkEvidence")
    javaLauncher.set(componentLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

providers.gradleProperty("strata.performance.workloads").orNull?.let { selected ->
    verifyStressRenderingWork.configure { systemProperty("strata.performance.workloads", selected) }
}

tasks.named("check") { dependsOn(verifyStressRenderingWork) }

val verifyExceptionalTextWork = tasks.register<JavaExec>("verifyExceptionalTextWork") {
    group = "verification"
    description = "Checks supplemental exceptional TextField metrics, clean frames, real updates and terminal font release."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.ExceptionalTextWorkEvidence")
    javaLauncher.set(componentLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("check") { dependsOn(verifyExceptionalTextWork) }

val verifyFontWork = tasks.register<JavaExec>("verifyFontWork") {
    group = "verification"
    description = "Checks actual font providers, visual ordering, reference limits and bounded native/cache release."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.FontWorkEvidence")
    javaLauncher.set(componentLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("strata.performance.fontFixture", rootProject.file("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf").absolutePath)
}

tasks.named("check") { dependsOn(verifyFontWork) }

val verifyPortableTextWork = tasks.register<JavaExec>("verifyPortableTextWork") {
    group = "verification"
    description = "Checks detached Unicode and large custom-font glyph composition with changing destinations."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.PortableTextWorkEvidence")
    javaLauncher.set(componentLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("strata.performance.fontFixture", rootProject.file("runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf").absolutePath)
}

tasks.named("check") { dependsOn(verifyPortableTextWork) }

tasks.register<JavaExec>("captureRuntimeSurfaceInventory") {
    group = "verification"
    description = "Stages exact loaded portable runtime API assignments for review; verification never updates the baseline."
    val generated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
    val generator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
    dependsOn(generated, generator)
    classpath = sourceSets.named("jmh").get().runtimeClasspath + files(generated.flatMap { it.destinationDirectory }, generator.flatMap { it.generatedResourcesDir })
    mainClass.set("dev.s7a.strata.quality.benchmark.RuntimeSurfaceInventoryEvidence")
    javaLauncher.set(componentLauncher)
    args(layout.buildDirectory.file("performance/runtime-api.tsv").get().asFile.absolutePath)
}

// Compiler ABI checks bind the reviewed host/member surface to the actual publication model.
val publishedHostProjects = rootProject.file("gradle/performance-modules.tsv").readLines(Charsets.UTF_8)
    .filter { it.isNotBlank() && it.startsWith('#').not() }
    .map { it.substringBefore('\t') }.distinct()
val publishedAbiChecks = publishedHostProjects.map { "$it:checkKotlinAbi" }
tasks.register<JavaExec>("capturePublishedHostInventory") {
    group = "verification"
    description = "Stages exact compiler member/host assignments for review, without changing the checked-in registry."
    dependsOn("classes", "test", ":verifyPublishedPerformanceInventory", publishedAbiChecks)
    classpath = sourceSets.named("main").get().runtimeClasspath
    javaLauncher.set(componentLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.PublishedHostInventoryCapture")
    args(rootProject.projectDir.absolutePath, layout.buildDirectory.file("performance/prospective-published-host-api.tsv").get().asFile.absolutePath)
}
val verifyPublishedHostInventory = tasks.register<JavaExec>("verifyPublishedHostInventory") {
    group = "verification"
    description = "Rejects new, removed and unassigned compiler API members on every published physical host."
    dependsOn("classes", "test", ":verifyPublishedPerformanceInventory", publishedAbiChecks)
    classpath = sourceSets.named("main").get().runtimeClasspath
    javaLauncher.set(componentLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.PublishedHostInventoryEvidence")
    args(rootProject.projectDir.absolutePath)
}
tasks.named("check") { dependsOn(verifyPublishedHostInventory) }

tasks.register<JavaExec>("processNativeComponentEvidence") {
    group = "verification"
    description = "Validates the profile's native component invocations and delegates all aggregation to the testkit."
    dependsOn("classes")
    classpath = sourceSets.named("main").get().runtimeClasspath
    javaLauncher.set(componentLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.NativeComponentPerformanceEvidence")
    providers.gradleProperty("strata.performance.request").orNull?.let { args(rootProject.file(it).absolutePath) }
}
