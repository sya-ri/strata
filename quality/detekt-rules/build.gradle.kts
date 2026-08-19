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
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    dependsOn(tasks.jar)
}
