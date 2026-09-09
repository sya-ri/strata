plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    add("jmh", project(":api"))
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
