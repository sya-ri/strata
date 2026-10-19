plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api12111)
    minecraft(libs.minecraft12111)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
