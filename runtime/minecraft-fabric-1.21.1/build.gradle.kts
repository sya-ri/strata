plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api1211)
    minecraft(libs.minecraft1211)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
