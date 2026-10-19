plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api120)
    minecraft(libs.minecraft120)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
