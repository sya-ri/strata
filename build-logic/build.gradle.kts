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
}
