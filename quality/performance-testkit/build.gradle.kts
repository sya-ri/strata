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
