dependencies {
    api(project(":api"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
