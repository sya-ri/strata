plugins {
    `java-library`
    alias(libs.plugins.fabricLoomRemap)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

dependencies {
    modImplementation(libs.fabric.api1201)
    minecraft(libs.minecraft1201)
    mappings(loom.officialMojangMappings())
    modCompileOnly(libs.fabric.loader)
}
