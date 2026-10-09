import dev.detekt.gradle.extensions.DetektExtension
import me.champeau.jmh.JMHTask
import me.champeau.jmh.JmhBytecodeGeneratorTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.Properties

plugins { alias(libs.plugins.jmh) }
extensions.configure<DetektExtension> { source.from("src/jmh/kotlin") }
dependencies {
    add("jmh", project(":api"))
    add("jmh", project(":runtime:core"))
    add("jmh", project(":runtime:remote"))
    add("jmh", project(":performance-testkit"))
}
jmh { jmhVersion.set(libs.versions.benchmark.harness) }

val remoteGenerated = tasks.named<JavaCompile>("jmhCompileGeneratedClasses")
val remoteGenerator = tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator")
val remoteClasspath = sourceSets.named("jmh").get().runtimeClasspath + files(remoteGenerated.flatMap { it.destinationDirectory }, remoteGenerator.flatMap { it.generatedResourcesDir })
val remoteLauncher = tasks.named<JMHTask>("jmh").flatMap { it.javaLauncher }

val verifyRemoteWorkloads by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks actual remote trees, codecs and framing against the shared generated JMH inventory."
    dependsOn(remoteGenerated, remoteGenerator)
    classpath = remoteClasspath
    javaLauncher.set(remoteLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.RemoteWorkEvidence")
}

tasks.named("check") { dependsOn(verifyRemoteWorkloads) }

tasks.register<JavaExec>("jmhRemote") {
    providers.gradleProperty("strata.performance.quick").orNull?.let { systemProperty("strata.performance.quick", it) }
    listOf("cpuAdmission", "cpuContext").forEach { name ->
        providers.gradleProperty("strata.performance.$name").orNull?.let { systemProperty("strata.performance.$name", rootProject.file(it).absolutePath) }
    }
    if (providers.gradleProperty("strata.performance.cpuAdmission").isPresent || providers.gradleProperty("strata.performance.cpuContext").isPresent) {
        systemProperty("strata.performance.cpuProbe", rootProject.file(providers.gradleProperty("strata.performance.cpuProbe").getOrElse("performance-testkit/tools/cpu_host.py")).absolutePath)
    }
    group = "verification"
    description = "Collects actual remote protocol JMH receipts through the shared kit."
    dependsOn(remoteGenerated, remoteGenerator)
    classpath = remoteClasspath
    javaLauncher.set(remoteLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.RemotePerformanceEvidence")
    val repetition = providers.gradleProperty("strata.performance.repetition").map(String::toInt).getOrElse(0)
    require(0 <= repetition)
    val quick = providers.gradleProperty("strata.performance.quick").map(String::toBooleanStrict).getOrElse(false)
    val smoke = providers.gradleProperty("strata.performance.smoke").map(String::toBooleanStrict).getOrElse(false)
    val short = quick || smoke
    val mode = providers.gradleProperty("strata.performance.mode").getOrElse("avgt")
    require(mode in setOf("avgt", "sample"))
    val sessions = providers.gradleProperty("strata.performance.remoteSessions").map(String::toBooleanStrict).getOrElse(false)
    val benchmarks = providers.gradleProperty("strata.performance.benchmarks").orNull
    val parameters = providers.gradleProperty("strata.performance.parameters").orNull
    val fixtureInputs = providers.gradleProperty("strata.performance.fixtureInputs").orNull
    require(benchmarks == null || (sessions.not() && smoke.not())) { "Explicit fixtures and legacy remote corpus selection are separate scopes" }
    require(parameters == null || benchmarks != null) { "Compiled parameter selection requires explicit fixtures" }
    require(fixtureInputs == null || benchmarks != null) { "Additional fixture inputs require explicit fixtures" }
    benchmarks?.let { systemProperty("strata.performance.benchmarks", it) }
    parameters?.let { systemProperty("strata.performance.parameters", rootProject.file(it).absolutePath) }
    fixtureInputs?.let { systemProperty("strata.performance.fixtureInputs", rootProject.file(it).absolutePath) }
    val family = if (benchmarks != null) "remote-selected" else if (sessions) "remote-sessions" else "remote"
    val workloads = providers.gradleProperty("strata.performance.workloads").orNull
    workloads?.let { systemProperty("strata.performance.workloads", it) }
    val suite = (if (quick) "$family-quick" else if (smoke) "$family-smoke" else family) + (if (workloads != null) "-selected" else "") + (if (mode in setOf("sample")) "-sample" else "")
    val includes = if (benchmarks != null) ".*" else if (sessions) "RemoteSessionBenchmark.*" else "RemoteProtocolBenchmark.*"
    val result = providers.gradleProperty("strata.performance.output").map { rootProject.file(it) }.orElse(layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition").map { it.asFile })
    args(result.get().absolutePath, repetition.toString(), includes, "-bm", mode, "-wi", if (short) "0" else "3", "-w", "1s", "-i", if (short) "1" else "5", "-r", if (short) "100ms" else "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc")
    systemProperty("strata.performance.remoteSessions", sessions)
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

tasks.register<JavaExec>("captureRemoteInventory") {
    group = "verification"
    description = "Stages exact loaded remote API registration for review without updating its baseline."
    dependsOn(remoteGenerated, remoteGenerator)
    classpath = remoteClasspath
    javaLauncher.set(remoteLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.RemoteWorkEvidence")
    args(layout.buildDirectory.file("performance/remote-api.tsv").get().asFile.absolutePath)
}

val verifyRemoteSessionWork by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks actual retained remote owners, shared revisions and terminal subscription release."
    dependsOn(remoteGenerated, remoteGenerator)
    classpath = remoteClasspath
    javaLauncher.set(remoteLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.RemoteSessionWorkEvidence")
}

tasks.named("check") { dependsOn(verifyRemoteSessionWork) }
