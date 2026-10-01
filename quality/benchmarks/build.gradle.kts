import dev.detekt.gradle.extensions.DetektExtension
import me.champeau.jmh.JMHTask
import me.champeau.jmh.JmhBytecodeGeneratorTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Properties

plugins {
    alias(libs.plugins.jmh)
}

extensions.configure<DetektExtension> { source.from("src/jmh/kotlin") }

dependencies {
    add("jmh", project(":api"))
    add("jmh", project(":quality:performance-testkit"))
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
    val smoke = providers.gradleProperty("strata.performance.smoke").map(String::toBooleanStrict).getOrElse(false)
    val mode = providers.gradleProperty("strata.performance.mode").getOrElse("avgt")
    require(mode in setOf("avgt", "sample"))
    val suite = (if (smoke) "historical-smoke" else "historical") + (if (mode in setOf("sample")) "-sample" else "")
    val includes = if (smoke) "RenderingBenchmark.cleanUiSessionFrame" else "(RenderingBenchmark|ReactiveRenderingBenchmark|OverlayRenderingBenchmark).*"
    val result = layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition")
    args(result.get().asFile.absolutePath, repetition.toString(), includes, "-bm", mode, "-wi", if (smoke) "0" else "3", "-w", "1s", "-i", if (smoke) "1" else "5", "-r", if (smoke) "100ms" else "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc")
    systemProperty("strata.performance.smoke", smoke)
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
