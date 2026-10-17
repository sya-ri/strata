import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

extensions.configure<DetektExtension> {
    source.from(
        rootProject.file("runtime/shared/minecraft-fabric/resources/gui-extractor/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftFontMapping.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/input/gui-extractor/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftFocusedInputMapping.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/screen/gui-extractor/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftScreen.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/resources/gui-extractor/src/test/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftFontContractTest.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/input/gui-extractor/src/test/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftContainerInputTest.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/input/glfw-observation/src/test/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftInputMappingTest.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/lifecycle/glfw-observation/src/test/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftJvmSurfaceTest.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/rendering/gui-extractor/src/test/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftSampledBoundsTest.kt"),
        rootProject.file("runtime/shared/minecraft-fabric/resources/gui-extractor/src/test/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftTextMappingTest.kt"),
    )
}

extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDir(rootProject.file("runtime/shared/minecraft-fabric/screen/tooltip/gui-extractor/src/main/java"))
        java.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/input/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/lifecycle/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/transport/common/src/main/java"),
        )
        java.srcDir(rootProject.file("runtime/shared/minecraft-fabric/resources/identifier/src/main/java"))
        java.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/input/gui-extractor/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/gui-extractor/src/main/java"),
        )
        java.srcDir("src/version/java")
    }
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
    sourceSets.named("main") {
        kotlin.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/input/common/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/lifecycle/common/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/rendering/common/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/common/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/transport/common/src/main/kotlin"),
        )
        kotlin.srcDir(rootProject.file("runtime/shared/minecraft-fabric/resources/identifier/src/main/kotlin"))
        kotlin.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/input/gui-extractor/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/rendering/gui-extractor/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/gui-extractor/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/gui-extractor/src/main/kotlin"),
        )
        kotlin.srcDir(rootProject.file("runtime/shared/minecraft-fabric/input/glfw-observation/src/main/kotlin"))
    }
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

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    inputs.property("fabricLoaderVersion", libs.versions.fabric.loader)
    inputs.property("minecraftVersion", libs.versions.minecraft262)
    inputs.property("fabricLanguageKotlinVersion", libs.versions.fabric.language.kotlin)
    inputs.property("javaVersion", libs.versions.java.minecraft)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "fabricLoader" to libs.versions.fabric.loader.get(),
            "minecraft" to libs.versions.minecraft262.get(),
            "fabricLanguageKotlin" to libs.versions.fabric.language.kotlin.get(),
            "java" to libs.versions.java.minecraft.get(),
        )
    }
}


dependencies {
    testImplementation(project(":runtime:remote"))
    compileOnly(project(":runtime:remote"))
    compileOnly(project(":runtime:minecraft"))
    compileOnly(project(":runtime:minecraft-fonts-lwjgl"))
    compileOnly(project(":runtime:headless"))
    minecraft(libs.minecraft262)
    compileOnly(libs.fabric.loader)
    runtimeOnly(libs.fabric.language.kotlin)
    include(project(":api"))
    include(project(":runtime:core"))
    include(project(":runtime:remote"))
    include(project(":runtime:headless"))
    include(project(":runtime:minecraft"))
    include(project(":runtime:minecraft-fonts-lwjgl"))
    testImplementation(project(":runtime:minecraft"))
    testImplementation(project(":runtime:minecraft-fonts-lwjgl"))
    testImplementation(project(":runtime:headless"))
    testRuntimeOnly(libs.fabric.loader)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

loom {
    mods {
        register("strata") {
            sourceSet(sourceSets.main.get())
        }
    }
}
