group = "dev.s7a.strata.examples"

dependencies {
    compileOnly(project(":paper-api"))
    compileOnly(libs.paper.api)
    // The companion client extension example registers native factories through the transport SPI.
    compileOnly(project(":runtime:remote"))
    testImplementation(project(":runtime:remote"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
