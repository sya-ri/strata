// The workflow supplies this controller to immutable subjects, including revisions predating it.
// Run with --no-configuration-cache so admission observes every selected execution graph.
gradle.projectsEvaluated {
    if (gradle.parent == null) {
        val versions = rootProject.providers.gradleProperty("strata.minecraftVersions").get().split(",")
        val backend = rootProject.providers.gradleProperty("strata.canvas.backend").get()
        require(backend in setOf("opengl", "vulkan")) { "Select an explicit client backend." }
        gradle.taskGraph.whenReady {
            versions.forEach { version ->
                val owner = ":integration:minecraft-fabric-$version"
                check(hasTask("$owner:check")) { "Selected client verification must run $owner:check." }
                listOf("runClientGameTest", "runProductionClientGameTest").forEach { name ->
                    val path = "$owner:$name"
                    val client = checkNotNull(allTasks.singleOrNull { it.path == path }) { "Selected check omits $path." }
                    check(client.enabled) { "Selected client task is disabled: $path." }
                    val inputs = client.inputs.properties
                    check(inputs["strataCanvasBackend"] == backend && inputs["strataCanvasSuiteScope"] == "full") {
                        "$path does not declare full $backend client verification."
                    }
                }
                rootProject.logger.lifecycle("Admitted Minecraft $version full $backend development and production clients.")
            }
        }
    }
}
