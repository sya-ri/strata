import org.gradle.api.artifacts.component.ProjectComponentIdentifier

group = "dev.s7a.strata.integration"

dependencies {
    compileOnly(project(":api"))
    testImplementation(project(":runtime:core"))
    testImplementation(project(":runtime:minecraft"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
