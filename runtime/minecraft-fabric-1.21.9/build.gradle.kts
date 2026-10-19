plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api1219)
    minecraft(libs.minecraft1219)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
