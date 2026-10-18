import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.fabric.api261)
    testImplementation(project(":runtime:remote"))
    minecraft(libs.minecraft261)
    compileOnly(libs.fabric.loader)
}
