import java.util.zip.CRC32
import java.util.zip.ZipFile

dependencies {
    api(project(":runtime:remote"))
    compileOnly(libs.velocity.api)
    testImplementation(libs.velocity.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") { expand("version" to project.version) }
}

val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Packages the installable Strata plugin with its Kotlin and shared runtime dependencies."
    archiveBaseName.set("strata-runtime-velocity")
    archiveClassifier.set("plugin")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { dependencies -> dependencies.map(::zipTree) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.assemble { dependsOn(pluginJar) }

publishing.publications.withType<MavenPublication>().configureEach { artifact(pluginJar) }

val verifyPluginJar = tasks.register("verifyPluginJar") {
    group = "verification"
    description = "Verifies the installable plugin descriptor, shared runtime dependencies, and platform API exclusion."
    val archive = pluginJar.flatMap { it.archiveFile }
    inputs.file(archive)
    doLast {
        ZipFile(archive.get().asFile).use { jar ->
            val entries = jar.entries().asSequence().toList()
            val names = entries.map { it.name }.toSet()
            check(names.size == entries.size) { "The Velocity distribution contains duplicate entries." }
            val required = setOf(
                "velocity-plugin.json",
                "dev/s7a/strata/runtime/velocity/StrataVelocityPlugin.class",
                "dev/s7a/strata/runtime/velocity/VelocityScreens.class",
                "dev/s7a/strata/runtime/remote/RemoteConnection.class",
                "dev/s7a/strata/runtime/spi/RuntimeUiSession.class",
                "dev/s7a/strata/screen/ScreenDefinition.class",
                "kotlin/Unit.class",
                "kotlinx/coroutines/Job.class",
            )
            check(names.containsAll(required)) { "Missing plugin dependencies: ${required - names}" }
            val platformRoots = listOf("org/bukkit/", "io/papermc/", "com/velocitypowered/", "com/google/inject/", "net/minecraft/", "net/fabricmc/")
            check(names.none { name -> platformRoots.any(name::startsWith) }) { "Platform classes must not be shaded into the Velocity plugin." }
            val descriptor = jar.getInputStream(jar.getEntry("velocity-plugin.json")).bufferedReader().use { it.readText() }
            check(descriptor.contains("\"main\": \"dev.s7a.strata.runtime.velocity.StrataVelocityPlugin\""))
            check(descriptor.contains("\"version\": \"${project.version}\""))
            entries.filter { it.isDirectory.not() }.forEach { entry ->
                val crc = CRC32()
                jar.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    var count = input.read(buffer)
                    while (0 <= count) {
                        crc.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                check(crc.value == entry.crc) { "Invalid archive CRC: ${entry.name}" }
            }
        }
    }
}

tasks.check { dependsOn(verifyPluginJar) }
