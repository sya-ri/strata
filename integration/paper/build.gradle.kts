group = "dev.s7a.strata.integration"

dependencies {
    compileOnly(project(":runtime:paper"))
    compileOnly(project(":examples:paper"))
    compileOnly(libs.paper.api)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
