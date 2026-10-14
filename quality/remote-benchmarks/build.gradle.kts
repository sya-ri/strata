import dev.detekt.gradle.extensions.DetektExtension
import dev.s7a.strata.gradle.performance.configureJmhEvidence
import me.champeau.jmh.JMHTask
import me.champeau.jmh.JmhBytecodeGeneratorTask

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
    val selected = benchmarks != null || parameters != null
    val label = providers.gradleProperty("strata.performance.suite").getOrElse(family)
    require(label.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid performance output suite name" }
    val suite = (if (quick) "$label-quick" else if (smoke) "$label-smoke" else label) + (if (workloads != null || selected) "-selected" else "") + (if (mode in setOf("sample")) "-sample" else "")
    val includes = if (benchmarks != null) ".*" else if (sessions) "RemoteSessionBenchmark.*" else "RemoteProtocolBenchmark.*"
    val result = providers.gradleProperty("strata.performance.output").map { rootProject.file(it) }.orElse(layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition").map { it.asFile })
    configureJmhEvidence(result.get(), repetition, includes, mode, short)
    systemProperty("strata.performance.remoteSessions", sessions)
    systemProperty("strata.performance.smoke", smoke || (quick && workloads == null && selected.not()))
    systemProperty("strata.performance.mode", mode)
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
