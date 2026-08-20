group = "dev.s7a.strata.integration"

dependencies {
    testImplementation(rootProject.project(":api"))
    testImplementation(rootProject.project(":runtime:core"))
    testImplementation(rootProject.project(":runtime:minecraft"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
