dependencies {
    api(project(":runtime:core"))
    testImplementation(project(":runtime:headless"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
