plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.detekt.test.utils)
    testImplementation(project(":api"))
    testImplementation(project(":performance-testkit"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Strata Detekt rules")
            description.set("Type-aware authoring checks for Strata components, modifiers, retained state, and UI trees.")
        }
    }
}

tasks.test {
    systemProperty("strata.authoringGuide", rootProject.file("docs/guides/authoring-checks.md").absolutePath)
    systemProperty("strata.authoringImports", rootProject.file("release/authoring-checks/imports.txt").absolutePath)
    systemProperty("strata.authoringPerformanceReport", layout.buildDirectory.file("reports/performance/rule-pass.json").get().asFile.absolutePath)
    inputs.file(rootProject.file("docs/guides/authoring-checks.md"))
    inputs.file(rootProject.file("release/authoring-checks/imports.txt"))
    outputs.file(layout.buildDirectory.file("reports/performance/rule-pass.json"))
}
