plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api1217)
    minecraft(libs.minecraft1217)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
