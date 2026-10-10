import dev.detekt.gradle.extensions.DetektExtension
import me.champeau.jmh.JMHTask
import me.champeau.jmh.JmhBytecodeGeneratorTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.util.Properties

plugins {
    alias(libs.plugins.jmh)
}

extensions.configure<DetektExtension> { source.from("src/jmh/kotlin") }

sourceSets.named("jmh") { kotlin.srcDir("src/fixture/kotlin") }
extensions.configure<DetektExtension> { source.from("src/fixture/kotlin") }

dependencies {
    add("jmh", project(":api"))
    add("jmh", project(":performance-testkit"))
    add("jmh", project(":runtime:core"))
    add("jmh", project(":runtime:headless"))
}

jmh {
    jmhVersion.set(libs.versions.benchmark.harness)
    includes.set(listOf("dev\\.s7a\\.strata\\.quality\\.benchmark\\.(RenderingBenchmark|ReactiveRenderingBenchmark|OverlayRenderingBenchmark).*"))
    benchmarkMode.set(listOf("avgt"))
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    fork.set(1)
    threads.set(1)
    timeUnit.set("us")
    failOnError.set(true)
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json").get().asFile)
}

val verifyReactiveRenderingWork by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks deterministic reactive work and retention using the same fixtures as JMH."
    dependsOn(tasks.named("jmhClasses"))
    classpath = sourceSets.named("jmh").get().runtimeClasspath
    mainClass.set("dev.s7a.strata.quality.benchmark.ReactiveWorkEvidence")
}

tasks.named("check") { dependsOn(verifyReactiveRenderingWork) }

val verifyOverlayRenderingWork by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies long-lived lower-layer updates and exact translucent composition with bounded retention."
    dependsOn(tasks.named("jmhClasses"))
    classpath = sourceSets.named("jmh").get().runtimeClasspath
    mainClass.set("dev.s7a.strata.quality.benchmark.OverlayWorkEvidence")
}

tasks.named("check") { dependsOn(verifyOverlayRenderingWork) }

val historicalGenerated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
val historicalGenerator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
val historicalClasspath = sourceSets.named("jmh").get().runtimeClasspath + files(historicalGenerated.flatMap { it.destinationDirectory }, historicalGenerator.flatMap { it.generatedResourcesDir })
val historicalLauncher = tasks.named<JMHTask>("jmh").flatMap { it.javaLauncher }

tasks.register<JavaExec>("jmhPortableTiles") {
    group = "verification"
    description = "Measures independent portable partitioning from an explicit Fabric runtime archive with shared-kit provenance."
    dependsOn(historicalGenerated, historicalGenerator)
    classpath = historicalClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.java.minecraft.get().toInt())) })
    mainClass.set("dev.s7a.strata.quality.benchmark.PortableTilePerformanceEvidence")
    val repetition = providers.gradleProperty("strata.performance.repetition").map(String::toInt).getOrElse(0)
    require(0 <= repetition)
    val controls = providers.gradleProperty("strata.performance.portableTileControls").map(String::toBooleanStrict).getOrElse(false)
    systemProperty("strata.performance.portableTileControls", controls)
    val suite = if (controls) "portable-tile-controls" else "portable-tiles"
    val output = providers.gradleProperty("strata.performance.portableTileOutput")
        .map { rootProject.file(it).resolve("run-$repetition") }
        .orElse(layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition").map { it.asFile })
    val benchmark = if (controls) "PortableTileControlBenchmark.partition" else "PortableTileBenchmark.partition"
    args(output.get().absolutePath, repetition.toString(), benchmark, "-bm", "avgt", "-wi", "3", "-w", "1s", "-i", "5", "-r", "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc")
    doFirst {
        val runtime = rootProject.file(checkNotNull(providers.gradleProperty("strata.performance.portableTileRuntime").orNull) { "Supply strata.performance.portableTileRuntime with the actual Fabric runtime JAR." }).canonicalFile
        require(runtime.isFile && runtime.extension == "jar") { "Portable tile collection requires an actual runtime JAR." }
        classpath += files(runtime)
        val entries = Properties()
        configurations.getByName("jmhRuntimeClasspath").incoming.artifacts.artifacts.forEach { artifact ->
            val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            if (module != null) {
                val label = "${module.group}:${module.module}:${module.version}:${artifact.file.name}"
                require(entries.setProperty(label, artifact.file.absolutePath) == null) { "Duplicate resolved control library: $label" }
            }
        }
        require(entries.isNotEmpty()) { "The JMH control library inventory is missing" }
        val manifest = layout.buildDirectory.file("performance/portable-tile-inputs.properties").get().asFile
        manifest.parentFile.mkdirs()
        manifest.bufferedWriter(Charsets.UTF_8).use { entries.store(it, "Resolved non-Strata control libraries") }
        systemProperty("strata.performance.inputs", manifest.absolutePath)
    }
}

val verifyHistoricalWorkloads by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks the complete generated historical JMH matrix with the shared inventory gate."
    dependsOn(historicalGenerated, historicalGenerator)
    classpath = historicalClasspath
    javaLauncher.set(historicalLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.HistoricalWorkloadEvidence")
}

tasks.named("check") { dependsOn(verifyHistoricalWorkloads) }

tasks.register<JavaExec>("jmhHistorical") {
    group = "verification"
    description = "Collects shared-kit JMH receipts with unchanged historical inputs and execution settings."
    dependsOn(historicalGenerated, historicalGenerator)
    classpath = historicalClasspath
    javaLauncher.set(historicalLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.HistoricalPerformanceEvidence")
    val repetition = providers.gradleProperty("strata.performance.repetition").map(String::toInt).getOrElse(0)
    require(0 <= repetition)
    val quick = providers.gradleProperty("strata.performance.quick").map(String::toBooleanStrict).getOrElse(false)
    val smoke = providers.gradleProperty("strata.performance.smoke").map(String::toBooleanStrict).getOrElse(false)
    val short = quick || smoke
    val mode = providers.gradleProperty("strata.performance.mode").getOrElse("avgt")
    require(mode in setOf("avgt", "sample"))
    require(listOf("semanticsFrame", "childLayout", "coldImage", "denseSampledRaster", "sampledRaster", "nonuniformOverlay").none { providers.gradleProperty("strata.performance.$it").isPresent }) { "Select generated fixture classes with strata.performance.benchmarks" }
    val selected = listOf("benchmarks", "workloads", "parameters").any { providers.gradleProperty("strata.performance.$it").isPresent }
    val label = providers.gradleProperty("strata.performance.suite").getOrElse(if (selected) "selected" else "historical")
    require(label.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid performance output suite name" }
    val suite = label + (if (smoke) "-smoke" else "") + (if (quick) "-quick" else "") + (if (mode == "sample") "-sample" else "")
    listOf("benchmarks", "workloads").forEach { name ->
        providers.gradleProperty("strata.performance.$name").orNull?.let { systemProperty("strata.performance.$name", it) }
    }
    listOf("parameters", "fixtureInputs").forEach { name ->
        providers.gradleProperty("strata.performance.$name").orNull?.let { systemProperty("strata.performance.$name", rootProject.file(it).absolutePath) }
    }
    val quickSmoke = quick && selected.not()
    val result = providers.gradleProperty("strata.performance.historicalOutputRoot")
        .map { rootProject.file(it).resolve("$suite/run-$repetition") }
        .orElse(layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition").map { it.asFile })
    args(result.get().absolutePath, repetition.toString(), ".*", "-bm", mode, "-wi", if (short) "0" else "3", "-w", "1s", "-i", if (short) "1" else "5", "-r", if (short) "100ms" else "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc")
    systemProperty("strata.performance.smoke", smoke || quickSmoke)
    systemProperty("strata.performance.mode", mode)
    val inputsManifest = layout.buildDirectory.file("performance/control-inputs.properties")
    doFirst {
        providers.gradleProperty("strata.performance.historicalRuntime").orNull?.let { path ->
            val targets = Properties()
            rootProject.file(path).bufferedReader(Charsets.UTF_8).use(targets::load)
            val projects = setOf(":api", ":runtime:core", ":runtime:headless")
            require(targets.stringPropertyNames() == projects) { "Register exactly the three historical runtime project paths" }
            val archives = projects.map { project -> rootProject.file(targets.getProperty(project)).canonicalFile }
            require(archives.toSet().size == projects.size && archives.all { it.isFile && it.extension == "jar" }) { "Historical runtime targets must be three distinct actual JARs" }
            val artifacts = configurations.getByName("jmhRuntimeClasspath").incoming.artifacts.artifacts
                .filter { (it.id.componentIdentifier as? ProjectComponentIdentifier)?.projectPath in projects }
            require(artifacts.map { (it.id.componentIdentifier as ProjectComponentIdentifier).projectPath }.toSet() == projects) { "Historical runtime classpath inventory changed" }
            val replaced = artifacts.map { it.file.canonicalFile }.toSet()
            classpath = files(classpath.files.filter { it.canonicalFile !in replaced }) + files(archives)
            // The kit verifies the actual loaded class origins and preserves these targets; names never certify identity.
        }
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

tasks.register<JavaExec>("captureHeadlessInventory") {
    group = "verification"
    description = "Stages exact loaded headless API registration for review without updating its baseline."
    dependsOn(historicalGenerated, historicalGenerator)
    classpath = historicalClasspath
    javaLauncher.set(historicalLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.HistoricalWorkloadEvidence")
    args(layout.buildDirectory.file("performance/headless-api.tsv").get().asFile.absolutePath)
}
