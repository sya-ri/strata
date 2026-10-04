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
    add("jmh", project(":quality:performance-testkit"))
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
    group = "verification"
    description = "Collects actual remote protocol JMH receipts through the shared kit."
    dependsOn(remoteGenerated, remoteGenerator)
    classpath = remoteClasspath
    javaLauncher.set(remoteLauncher)
    mainClass.set("dev.s7a.strata.quality.benchmark.RemotePerformanceEvidence")
    val repetition = providers.gradleProperty("strata.performance.repetition").map(String::toInt).getOrElse(0)
    require(0 <= repetition)
    val smoke = providers.gradleProperty("strata.performance.smoke").map(String::toBooleanStrict).getOrElse(false)
    val mode = providers.gradleProperty("strata.performance.mode").getOrElse("avgt")
    require(mode in setOf("avgt", "sample"))
    val sessions = providers.gradleProperty("strata.performance.remoteSessions").map(String::toBooleanStrict).getOrElse(false)
    val family = if (sessions) "remote-sessions" else "remote"
    val workloads = providers.gradleProperty("strata.performance.workloads").orNull
    workloads?.let { systemProperty("strata.performance.workloads", it) }
    val suite = (if (smoke) "$family-smoke" else family) + (if (workloads != null) "-selected" else "") + (if (mode in setOf("sample")) "-sample" else "")
    val includes = if (sessions) "RemoteSessionBenchmark.*" else "RemoteProtocolBenchmark.*"
    val result = providers.gradleProperty("strata.performance.output").map { rootProject.file(it) }.orElse(layout.buildDirectory.dir("reports/jmh/$suite/run-$repetition").map { it.asFile })
    args(result.get().absolutePath, repetition.toString(), includes, "-bm", mode, "-wi", if (smoke) "0" else "3", "-w", "1s", "-i", if (smoke) "1" else "5", "-r", if (smoke) "100ms" else "1s", "-f", "1", "-t", "1", "-tu", "us", "-foe", "true", "-prof", "gc")
    systemProperty("strata.performance.remoteSessions", sessions)
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
