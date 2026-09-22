group = "dev.s7a.strata.integration"

dependencies {
    compileOnly(project(":runtime:velocity"))
    compileOnly(libs.velocity.api)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") { expand("version" to project.version) }
}
