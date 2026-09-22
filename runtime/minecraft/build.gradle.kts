dependencies {
    api(project(":runtime:core"))
    compileOnly(libs.gson.minecraft)
    testImplementation(libs.gson.minecraft)
    testImplementation(project(":runtime:headless"))
    testImplementation(project(":runtime:remote"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
