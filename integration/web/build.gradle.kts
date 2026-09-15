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
