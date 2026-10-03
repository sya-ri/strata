import dev.detekt.gradle.extensions.DetektExtension

group = "dev.s7a.strata.integration"

dependencies {
    compileOnly(project(":runtime:paper"))
    compileOnly(project(":examples:paper"))
    compileOnly(libs.paper.api)
    compileOnly(project(":quality:performance-testkit"))
    runtimeOnly(project(":quality:performance-testkit"))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":quality:performance-testkit"))
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.gson.minecraft)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.register<JavaExec>("processPerformanceEvidence") {
    group = "verification"
    description = "Verifies three real Paper owner runs through the packaged JVM testkit."
    val fixture = tasks.named<Jar>("jar")
    dependsOn(fixture)
    classpath = files(fixture.flatMap { it.archiveFile }) + configurations.runtimeClasspath.get()
    mainClass.set("dev.s7a.strata.integration.paper.PaperPerformanceEvidence")
    doFirst {
        args(rootProject.file(providers.gradleProperty("strata.performance.request").get()).absolutePath)
    }
}

kotlin.sourceSets.named("main") { kotlin.srcDir(rootProject.file("integration/server-performance/src/main/kotlin")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(rootProject.file("integration/server-performance/src/test/kotlin")) }
extensions.configure<DetektExtension> { source.from(rootProject.file("integration/server-performance/src/main/kotlin")) }

tasks.withType<Test>().configureEach {
    val collector = project(":quality:performance-testkit").tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }
    dependsOn(collector)
    inputs.file(collector)
    systemProperty("strata.test.performanceKit", collector.get().asFile.absolutePath)
    systemProperty("strata.test.fixtureClasses", layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
