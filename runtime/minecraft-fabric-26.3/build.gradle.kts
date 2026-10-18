import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

plugins {
    `java-library`
    alias(libs.plugins.fabricLoom)
}

apply(from = rootProject.file("runtime/minecraft-fabric-publication.gradle.kts"))

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

extensions.configure<SourceSetContainer> {
    named("main") { java.srcDir("src/version/java") }
}

dependencies {
    implementation(libs.fabric.api263)
    minecraft(libs.minecraft263)
    compileOnly(libs.fabric.loader)
    testImplementation(project(":runtime:remote"))
}

loom {
    accessWidenerPath.set(rootProject.file("runtime/shared/minecraft-fabric/canvas/renderpearl/src/main/resources/strata-sampled-gui.accesswidener"))
}
