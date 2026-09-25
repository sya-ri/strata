import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

extensions.configure<DetektExtension> {
    source.from(rootProject.file("integration/shared/font-parity/src/gametest/kotlin"))
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("integration/shared/font-parity/src/gametest/kotlin"))
    }
}

extensions.configure<SourceSetContainer> {
    named("test") {
        resources.srcDir(rootProject.file("integration/shared/font-parity/src/gametest/resources"))
    }
}

tasks.named<ProcessResources>("processTestResources") {
    from("src/test/resources/fonts/strata-test.ttf") {
        into("assets/strata_font_test/font")
    }
}

dependencies {
    api(project(":runtime:minecraft"))
    compileOnly(libs.lwjgl.font.core)
    compileOnly(libs.lwjgl.font.stb)
    compileOnly(libs.lwjgl.font.freetype)
    compileOnly(libs.icu.minecraft.baseline)
    testImplementation(libs.lwjgl.font.core)
    testImplementation(libs.lwjgl.font.stb)
    testImplementation(libs.lwjgl.font.freetype)
    testImplementation(libs.icu.minecraft.baseline)
    testImplementation(libs.gson.minecraft)
    testImplementation(project(":runtime:headless"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

apply(from = "verification.gradle.kts")
