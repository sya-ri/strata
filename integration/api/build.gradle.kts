import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.io.File

group = "dev.s7a.strata.integration"

dependencies {
    compileOnly(rootProject.project(":api"))
    testImplementation(rootProject.project(":runtime:core"))
    testImplementation(rootProject.project(":runtime:minecraft"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val sharedApiExamples = objects.sourceDirectorySet("sharedApiExamples", "Shipped API-only component declarations").apply {
    srcDir(rootProject.file("integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin"))
    include("**/*Example.kt")
    exclude("**/MinecraftInventoryExample.kt", "**/MinecraftSocialExample.kt")
}
val stagedSharedApiExamples = layout.buildDirectory.dir("generated/shared-api-examples/kotlin")
val stageSharedApiExamples = tasks.register<Sync>("stageSharedApiExamples") {
    description = "Stages the selected shipped examples under the API-only consumer's own source root."
    from(sharedApiExamples)
    into(stagedSharedApiExamples)
}
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("main") { kotlin.srcDir(stageSharedApiExamples) }
}
tasks.named<Test>("test") {
    inputs.files(sharedApiExamples)
    systemProperty("strata.sharedApiExampleSources", sharedApiExamples.files.map { it.absolutePath }.sorted().joinToString(File.pathSeparator))
    systemProperty("strata.stagedSharedApiExamples", stagedSharedApiExamples.get().asFile.absolutePath)
}

val checkApiOnlyClasspath =
    tasks.register("checkApiOnlyClasspath") {
        group = "verification"
        description = "Verifies that application authoring main sources compile with only the API project."
        dependsOn("compileKotlin")
        doLast {
            val projectDependencies =
                configurations
                    .getByName("compileClasspath")
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { component -> (component.id as? ProjectComponentIdentifier)?.projectPath }
                    .filter { projectPath -> projectPath != project.path }
                    .toSet()
            require(projectDependencies == setOf(":api")) {
                "API-only authoring compile classpath contains project dependencies: $projectDependencies"
            }
        }
    }

tasks.named("check") {
    dependsOn(checkApiOnlyClasspath)
}
