group = "dev.s7a.strata.integration"

dependencies {
    testImplementation(rootProject.project(":api"))
    testImplementation(rootProject.project(":runtime:core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
