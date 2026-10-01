import org.gradle.api.artifacts.component.ModuleComponentIdentifier

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

val pythonEvidenceTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies evidence comparison using the Python standard library and the actual packaged testkit."
    val archive = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }
    dependsOn(archive)
    inputs.file(archive)
    inputs.dir("src/pythonTest")
    workingDir(projectDir)
    commandLine("python", "-X", "utf8", "-m", "unittest", "discover", "-s", "src/pythonTest", "-v")
    doFirst {
        environment("STRATA_PERFORMANCE_TESTKIT_JAR", archive.get().asFile.absolutePath)
        val harness = configurations.getByName("jvmTestRuntimeClasspath").incoming.artifacts.artifacts.single { artifact ->
            val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            module?.group == "org.openjdk.jmh" && module.module == "jmh-core"
        }
        environment("STRATA_JMH_CORE_JAR", harness.file.absolutePath)
    }
}

tasks.named("check") { dependsOn(pythonEvidenceTest) }

