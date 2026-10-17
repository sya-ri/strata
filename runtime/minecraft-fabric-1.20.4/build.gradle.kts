import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDir(rootProject.file("runtime/shared/minecraft-fabric/screen/tooltip/empty-carried-gui-graphics/src/main/java"))
        java.srcDirs(
            rootProject.file("runtime/shared/minecraft-fabric/input/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/input/primitive-callbacks/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/lifecycle/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/transport/common/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/input/gui-graphics/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/gui-graphics/src/main/java"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/gui-graphics/src/main/java"),
        )
        java.srcDir(rootProject.file("runtime/shared/minecraft-fabric/rendering/render-type/src/main/java"))
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
            rootProject.file("runtime/shared/minecraft-fabric/input/gui-graphics/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/rendering/gui-graphics/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/gui-graphics/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/resources/primitive-callbacks/src/main/kotlin"),
            rootProject.file("runtime/shared/minecraft-fabric/screen/primitive-callbacks/src/main/kotlin"),
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    inputs.property("fabricLoaderVersion", libs.versions.fabric.loader)
    inputs.property("minecraftVersion", libs.versions.minecraft1204)
    inputs.property("fabricLanguageKotlinVersion", libs.versions.fabric.language.kotlin)
    inputs.property("javaVersion", libs.versions.java.baseline)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "fabricLoader" to libs.versions.fabric.loader.get(),
            "minecraft" to libs.versions.minecraft1204.get(),
            "fabricLanguageKotlin" to libs.versions.fabric.language.kotlin.get(),
            "java" to libs.versions.java.baseline.get(),
        )
    }
}


dependencies {
    compileOnly(project(":runtime:remote"))
    compileOnly(project(":runtime:minecraft"))
    compileOnly(project(":runtime:minecraft-fonts-lwjgl"))
    compileOnly(project(":runtime:headless"))
    minecraft(libs.minecraft1204)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
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
