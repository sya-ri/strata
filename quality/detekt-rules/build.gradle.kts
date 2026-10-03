plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.detekt.api)
    compileOnly(libs.detekt.test)
    testCompileOnly(libs.detekt.api)
    testCompileOnly(libs.detekt.test)
    testRuntimeOnly(libs.detekt.api)
    testRuntimeOnly(libs.detekt.test) {
        isTransitive = false
    }
    testRuntimeOnly(libs.detekt.test.utils)
    testImplementation(libs.detekt.utils)
    testImplementation(libs.detekt.core)
    testImplementation(libs.detekt.rules.style)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    dependsOn(tasks.jar)
    val config = rootProject.file("config/detekt/detekt.yml")
    inputs.file(config)
    systemProperty("strata.detekt.config", config.absolutePath)
}
