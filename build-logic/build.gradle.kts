import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    alias(libs.plugins.kotlinter)
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("strataRelease") {
            id = "dev.s7a.strata.release"
            implementationClass = "dev.s7a.strata.gradle.release.StrataReleasePlugin"
            displayName = "Strata release orchestration"
            description = "Builds and reconciles Strata release artifacts without publishing during ordinary builds."
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

tasks.test {
    useJUnitPlatform()
    // These production files are read by TestKit outside this included build's normal source inputs.
    inputs.files(
        "../build.gradle.kts",
        "../settings.gradle.kts",
        "../gradle/libs.versions.toml",
        "../gradle/performance/bounded-jvm.init.gradle.kts",
        "../gradle/verify-selected-clients.init.gradle.kts",
        "../integration/minecraft-fabric-published-runtime.gradle.kts",
        "../gradle/qodana-idea-model.gradle.kts",
        "../gradle/verify-qodana-declarations.py",
        "../gradle/prepare-qodana-project.py",
        "../gradle/tests/fixtures/assert_qodana_compiler_model.py",
    ).withPropertyName("externalFixtureSources").withPathSensitivity(PathSensitivity.RELATIVE)
    // Model fixtures use project membership; collector fixtures also read the benchmark script bodies.
    val fixtureRoot = layout.projectDirectory.dir("..").asFile
    inputs.property(
        "fixtureProjectPaths",
        fileTree(fixtureRoot) {
            include(
                "api/build.gradle.kts",
                "detekt-rules/build.gradle.kts",
                "performance-testkit/build.gradle.kts",
                "paper-api/build.gradle.kts",
                "velocity-api/build.gradle.kts",
                "runtime/*/build.gradle.kts",
                "integration/*/build.gradle.kts",
                "examples/*/build.gradle.kts",
                "quality/*/build.gradle.kts",
            )
        }.elements.map { files ->
            files.map { it.asFile.relativeTo(fixtureRoot).invariantSeparatorsPath }.sorted()
        },
    )
    inputs.files(
        fileTree(fixtureRoot) { include("quality/*benchmarks/build.gradle.kts") },
    ).withPropertyName("collectorFixtureSources").withPathSensitivity(PathSensitivity.RELATIVE)
    // TestKit releases its dedicated daemons when the class worker finishes.
    forkEvery = 1
}

// Exercise the same game-independent pacing and option-lifetime code used by loaded clients.
kotlin.sourceSets.named("test") {
    kotlin.srcDir("../integration/shared/minecraft-fabric/canvas/pacing/common/src/gametest/kotlin")
}
