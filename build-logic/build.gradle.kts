plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("strataRelease") {
            id = "dev.s7a.strata.release"
            implementationClass = "dev.s7a.strata.gradle.release.StrataReleasePlugin"
            displayName = "Strata release orchestration"
            description = "Builds and reconciles Strata release artifacts without publishing during ordinary builds."
        }
    }
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
