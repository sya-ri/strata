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
    includes.set(listOf("dev\\.s7a\\.strata\\.quality\\.benchmark\\.RenderingBenchmark.*"))
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
