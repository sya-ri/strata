plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.detekt.test.utils)
    testImplementation(project(":api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    archiveBaseName.set("strata-consumer-detekt-rules")
}
