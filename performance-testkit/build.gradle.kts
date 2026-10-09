import org.gradle.jvm.toolchain.JavaToolchainService

kotlin {
    sourceSets {
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmMain.dependencies {
            api(libs.gson.minecraft)
            compileOnly(libs.jmh.core)
        }
        jsMain.dependencies { implementation(libs.kotlinx.browser) }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.jmh.core)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Jar>("jvmJar") {
    // Removed resources can remain in incremental Copy outputs after upgrading an existing checkout.
    exclude("**/*.py", "**/*.pyc")
}

tasks.register<JavaExec>("processEvidence") {
    group = "verification"
    description = "Processes collector-bound evidence with the JVM testkit and no Python runtime."
    val collector = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }
    dependsOn(collector)
    classpath = files(collector) + configurations.getByName("jvmRuntimeClasspath")
    javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.java.baseline.get().toInt())) })
    mainClass.set("dev.s7a.strata.performance.PerformanceEvidenceCli")
    doFirst {
        val request = providers.gradleProperty("strata.performance.request").orNull
        require(request != null) { "Set strata.performance.request to one evidence request JSON file" }
        args(rootProject.file(request).absolutePath)
    }
}

tasks.named<Test>("jvmTest") {
    val collector = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }
    dependsOn(collector)
    inputs.file(collector)
    systemProperty("strata.testkit.jar", collector.get().asFile.absolutePath)
}

val verifyBrowserPerformanceDriver = tasks.register<Exec>("verifyBrowserPerformanceDriver") {
    group = "verification"
    description = "Checks independent browser invocations, evidence identity and failure cleanup in the shared driver."
    commandLine("node", "--test", layout.projectDirectory.file("src/jsTest/resources/browser-performance.test.mjs").asFile)
}

tasks.named("check") { dependsOn(verifyBrowserPerformanceDriver) }

val verifyCpuExecutorPlan = tasks.register<Exec>("verifyCpuExecutorPlan") {
    group = "verification"
    description = "Checks complete CPU executor plans and fail-closed whole-attempt adoption with synthetic sources."
    commandLine("python", "-B", "-m", "unittest", "discover", "-s", layout.projectDirectory.dir("tools/tests").asFile)
}

tasks.named("check") { dependsOn(verifyCpuExecutorPlan) }
