plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    runtimeOnly(gradleApi())
}

val output = providers.gradleProperty("portalOutput").get()
layout.buildDirectory.set(file(output).resolve("controller-build"))

kotlin.compilerOptions {
    allWarningsAsErrors.set(true)
    freeCompilerArgs.add("-Xexplicit-api=strict")
}

kotlin.sourceSets.main {
    kotlin.setSrcDirs(listOf(projectDir))
    kotlin.include("*.kt")
}

tasks.register<JavaExec>("verifyPortal") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.s7a.strata.gradle.release.PortalVerifier")
    args(
        providers.gradleProperty("portalOperation").get(),
        providers.gradleProperty("portalVersion").get(),
        providers.gradleProperty("portalCoordinates").get(),
        providers.gradleProperty("portalFiles").get(),
        providers.gradleProperty("portalRepository").get(),
        output,
    )
}
