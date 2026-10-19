plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api1214)
    minecraft(libs.minecraft1214)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
