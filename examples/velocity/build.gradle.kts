group = "dev.s7a.strata.examples"

dependencies {
    compileOnly(project(":velocity-api"))
    compileOnly(libs.velocity.api)
    testImplementation(project(":runtime:remote"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") { expand("version" to project.version) }
}
