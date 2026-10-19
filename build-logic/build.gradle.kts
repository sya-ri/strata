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
    maven("https://maven.fabricmc.net/")
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
    compileOnly(libs.fabric.loom.build)
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
        "../gradle/libs.versions.toml",
        "../gradle/qodana-idea-model.gradle.kts",
        "../gradle/verify-qodana-declarations.py",
        "../gradle/tests/fixtures/assert_qodana_compiler_model.py",
    ).withPropertyName("qodanaCompilerModel").withPathSensitivity(PathSensitivity.RELATIVE)
    // TestKit releases its dedicated daemons when the class worker finishes.
    forkEvery = 1
}
