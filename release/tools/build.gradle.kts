import groovy.json.JsonSlurper
import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    `kotlin-dsl`
    alias(libs.plugins.hangarPublish)
}

repositories { mavenCentral() }
dependencies { runtimeOnly(gradleApi()) }

kotlin.sourceSets.main {
    kotlin.srcDir("../../build-logic/src/main/kotlin")
    kotlin.include("dev/s7a/strata/gradle/release/**")
}
kotlin.compilerOptions {
    allWarningsAsErrors.set(true)
    freeCompilerArgs.add("-Xexplicit-api=strict")
}

val prepared = providers.gradleProperty("preparedRelease").map(::file)
val releaseOperation = providers.gradleProperty("releaseOperation").orElse("")
tasks.register<JavaExec>("reconcile") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.s7a.strata.gradle.release.PreparedReleaseVerifier")
    args(releaseOperation.get(), prepared.get().absolutePath)
}

hangarPublish {
    if (prepared.get().resolve("hangar/manifest.json").isFile) publications.register("strata") {
        val manifest = JsonSlurper().parse(prepared.get().resolve("hangar/manifest.json")) as Map<*, *>
        version.set(manifest["version"] as String)
        id.set(manifest["namespace"] as String)
        channel.set(manifest["channel"] as String)
        changelog.set(manifest["description"] as String)
        apiKey.set(providers.environmentVariable("HANGAR_API_TOKEN"))
        val artifacts = manifest["artifacts"] as Map<*, *>
        platforms {
            mapOf("PAPER" to Platforms.PAPER, "VELOCITY" to Platforms.VELOCITY).forEach { (name, platform) ->
                val artifact = artifacts[name] as Map<*, *>
                register(platform) {
                    jar.set(prepared.get().resolve(artifact["canonicalPath"] as String))
                    platformVersions.set((artifact["platformVersions"] as List<*>).map { it as String })
                }
            }
        }
    }
}
