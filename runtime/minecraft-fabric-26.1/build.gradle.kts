import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

extensions.configure<SourceSetContainer> {
    named("test") {
        java.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/canvas/gui-extractor/src/test/java"),
            rootProject.file("runtime/shared/minecraft-fabric/input/gui-extractor/src/test/java"),
            rootProject.file("runtime/shared/minecraft-fabric/rendering/gui-extractor/src/test/java"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/gui-extractor/src/test/java"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/gui-extractor/src/test/java"),
        )
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("test") {
        kotlin.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/canvas/gui-extractor/src/test/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/input/gui-extractor/src/test/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/rendering/gui-extractor/src/test/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/gui-extractor/src/test/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/gui-extractor/src/test/kotlin"),
        )
        kotlin.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/input/glfw-observation/src/test/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/lifecycle/glfw-observation/src/test/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/glfw-observation/src/test/kotlin"),
        )
    }
}

dependencies {
    implementation(libs.fabric.api261)
    testImplementation(project(":runtime:remote"))
    minecraft(libs.minecraft261)
    compileOnly(libs.fabric.loader)
}
