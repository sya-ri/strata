group = "dev.s7a.strata.examples"

dependencies {
    compileOnly(project(":runtime:paper"))
    compileOnly(libs.paper.api)
    testImplementation(project(":runtime:remote"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
