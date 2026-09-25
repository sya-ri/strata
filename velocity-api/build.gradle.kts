dependencies {
    api(project(":api"))
    compileOnly(libs.velocity.api)
    testImplementation(libs.velocity.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
