import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

dependencies {
    api(project(":runtime:core"))
    compileOnly(libs.gson.minecraft)
    testImplementation(libs.gson.minecraft)
    testImplementation(project(":runtime:headless"))
    testImplementation(project(":runtime:remote"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The complete game-independent reference root is shared with frozen benchmark and loaded-client fixtures.
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("test") { kotlin.srcDir(rootProject.file("integration/shared/image-backgrounds/src/test/kotlin")) }
}
