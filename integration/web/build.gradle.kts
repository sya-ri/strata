group = "dev.s7a.strata.integration"

kotlin {
    js {
        browser { commonWebpackConfig { outputFileName = "application.js" } }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies { implementation(project(":api")) }
        jsMain.dependencies {
            implementation(project(":runtime:web"))
            implementation(project(":quality:performance-testkit"))
            implementation(libs.kotlinx.browser)
        }
        jsTest.dependencies { implementation(npm("playwright", libs.versions.playwright.get())) }
        jvmTest.dependencies {
            implementation(project(":runtime:minecraft"))
            implementation(project(":runtime:headless"))
            implementation(libs.kotlin.test)
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// Reuse the exact API-only declarations and typed fixture inventory already exercised by JMH and Fabric.
kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir(rootProject.file("integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin"))
    kotlin.include("**/*Example.kt")
    kotlin.exclude("**/MinecraftInventoryExample.kt", "**/MinecraftSocialExample.kt")
    kotlin.srcDir(rootProject.file("quality/component-benchmarks/src/jmh/kotlin"))
    kotlin.include("**/ComponentWorkload.kt", "**/integration/web/*.kt")
}

val installWebBrowsers = tasks.register<Exec>("installWebBrowsers") {
    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
    commandLine("node", rootProject.layout.buildDirectory.file("js/node_modules/playwright/cli.js").get().asFile, "install", "chromium", "firefox", "webkit")
}

val buildWeb = tasks.register<Exec>("buildWeb") {
    group = "build"
    description = "Bundles the application and emits its independently rendered initial HTML."
    dependsOn("jsBrowserDistribution", installWebBrowsers)
    inputs.dir(layout.buildDirectory.dir("dist/js/productionExecutable"))
    inputs.file(rootProject.file("tools/web/build.mjs"))
    outputs.dir(layout.buildDirectory.dir("site"))
    commandLine("node", rootProject.file("tools/web/build.mjs"), "build", layout.buildDirectory.get().asFile)
}

val verifyWeb = tasks.register<Exec>("verifyWeb") {
    group = "verification"
    description = "Recreates browser evidence for the built application in three browser engines."
    dependsOn(buildWeb, "jvmTest")
    outputs.upToDateWhen { false }
    commandLine("node", rootProject.file("tools/web/build.mjs"), "verify", layout.buildDirectory.get().asFile)
}

tasks.named("assemble") { dependsOn(buildWeb) }
tasks.named("check") { dependsOn(verifyWeb) }
tasks.named<Test>("jvmTest") {
    outputs.file(layout.buildDirectory.file("parity/jvm.json"))
    outputs.upToDateWhen { false }
}

val measureWebPerformance = tasks.register<Exec>("measureWebPerformance") {
    group = "verification"
    description = "Measures the real Web host through the shared testkit in three browser engines."
    val collector = project(":quality:performance-testkit").tasks.named<Jar>("jsJar")
    dependsOn(buildWeb, collector)
    outputs.upToDateWhen { false }
    commandLine("node", rootProject.file("tools/web/build.mjs"), "performance", layout.buildDirectory.get().asFile, collector.get().archiveFile.get().asFile)
    doFirst {
        val output = providers.gradleProperty("strata.web.performanceOutput").orNull
        require(output != null) { "Set strata.web.performanceOutput to one new evidence JSON file" }
        args(rootProject.file(output).absolutePath)
    }
}
