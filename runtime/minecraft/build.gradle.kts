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

// The same game-independent scalar/pixel oracle is compiled into JVM, frozen benchmark and loaded-client fixtures.
val tileBackgroundReference = objects.sourceDirectorySet("tileBackgroundReference", "Independent original tiling and pixel reference").apply {
    srcDir(rootProject.file("integration/shared/minecraft-fabric/canvas/common/src/gametest/kotlin"))
    include("**/MinecraftTileBackgroundReference.kt")
}
extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("test") { kotlin.source(tileBackgroundReference) }
}
