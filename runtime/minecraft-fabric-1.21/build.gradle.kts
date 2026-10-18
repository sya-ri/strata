plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api121)
    minecraft(libs.minecraft121)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
