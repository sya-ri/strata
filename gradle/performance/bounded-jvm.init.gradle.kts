import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties

/** Hashes every bounded classpath byte, including resources and compiled control code. */
fun preparedIdentity(path: File): String {
    fun fileHash(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    check(Files.isSymbolicLink(path.toPath()).not())
    if (path.isFile) {
        check(path.length() <= 64L * 1024 * 1024)
        return fileHash(path)
    }
    check(path.isDirectory)
    val entries = mutableListOf<File>()
    var bytes = 0L
    path.walkTopDown().forEach { file ->
        check(Files.isSymbolicLink(file.toPath()).not())
        check(entries.size < 16_384)
        if (file.isFile) {
            bytes = Math.addExact(bytes, file.length())
            check(bytes <= 64L * 1024 * 1024)
            entries.add(file)
        }
    }
    val hash = MessageDigest.getInstance("SHA-256")
    entries.sortedBy { it.relativeTo(path).invariantSeparatorsPath }.forEach { file ->
        hash.update("${file.relativeTo(path).invariantSeparatorsPath}=${fileHash(file)}\n".toByteArray(Charsets.UTF_8))
    }
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Preserves a complete resolved classpath and verifies both origins and copies before publishing its pins. */
fun preservePreparedClasspath(files: Iterable<File>, retained: File): List<Map<String, String>> {
    check(retained.exists().not() && retained.mkdirs())
    val origins = files.toList()
    check(origins.size in 1..256)
    val entries = origins.mapIndexed { index, source ->
        val origin = source.canonicalFile
        val identity = preparedIdentity(origin)
        val archive = File(retained, if (origin.isDirectory) "classpath-$index" else "classpath-$index.jar")
        check(origin.copyRecursively(archive, overwrite = false))
        check(preparedIdentity(origin) == identity && preparedIdentity(archive) == identity)
        mapOf("source" to origin.absolutePath, "path" to archive.absolutePath, "sha256" to identity)
    }
    File(retained, "classpath.json").writeText(JsonOutput.toJson(mapOf("schema" to "strata-paired-jvm-classpath-v1", "entries" to entries)), Charsets.UTF_8)
    return entries
}

/** Preserves all origins, retaining explicit executed NO-SOURCE receipts for absent owned outputs. */
fun preserveOwnedClasspath(files: Iterable<File>, retained: File, tasks: List<Task>) {
    val original = files.map { it.canonicalFile }
    val absent = original.filter { it.exists().not() }.map { origin ->
        check(origin.extension.contentEquals("jar").not()) { "Missing resolved dependency archive: $origin" }
        val producers = tasks.filter { producer ->
            producer.state.executed && producer.state.noSource &&
                origin.toPath().startsWith(producer.project.layout.buildDirectory.get().asFile.canonicalFile.toPath()) &&
                origin in producer.outputs.files.files.map { it.canonicalFile }
        }
        check(producers.size == 1) { "Missing output without one executed NO-SOURCE producer: $origin" }
        mapOf("source" to origin.absolutePath, "task" to producers.single().path, "reason" to "NO-SOURCE")
    }
    preservePreparedClasspath(original.filter { it.exists() }, retained)
    File(retained, "original-classpath.json").writeText(JsonOutput.toJson(mapOf("sources" to original.map { it.absolutePath }, "absent_outputs" to absent)), Charsets.UTF_8)
}

// Apply the retained finite JVM profile to main and included builds; no benchmark registration is added.
gradle.projectsEvaluated {
    val workspace = File(checkNotNull(System.getenv("STRATA_PAIRED_WORKSPACE"))).canonicalFile
    check(rootProject.projectDir.canonicalFile in setOf(workspace, File(workspace, "build-logic").canonicalFile))
    check(gradle.startParameter.maxWorkerCount == 1 && gradle.startParameter.isDryRun.not())
    check(rootProject.providers.gradleProperty("kotlin.compiler.execution.strategy").get() == "in-process")
    check(rootProject.providers.gradleProperty("detekt.use.worker.api").get() == "false")
    check(Runtime.getRuntime().maxMemory() <= 768L * 1024L * 1024L)
    val daemonMetaspaceMiB = System.getenv("STRATA_PAIRED_DAEMON_METASPACE_MIB")?.toInt() ?: 1024
    check(daemonMetaspaceMiB in 1..1024)
    val daemonMetaspace = if (daemonMetaspaceMiB == 1024) "1g" else "${daemonMetaspaceMiB}m"
    check(ManagementFactory.getRuntimeMXBean().inputArguments.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=$daemonMetaspace")
    val vmLogs = File(checkNotNull(System.getenv("STRATA_PAIRED_LOGS"))).canonicalFile
    check(vmLogs.isDirectory && Files.isSymbolicLink(vmLogs.toPath()).not())
    // Gradle process-isolation workers discard the inherited environment on Windows.
    val workerDiagnostics = listOf(
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+PrintCommandLineFlags",
        "-XX:-DisplayVMOutput",
        "-XX:+LogVMOutput",
        "-XX:LogFile=${vmLogs.invariantSeparatorsPath}/vm-%p-%t.log",
    )
    rootProject.allprojects.forEach { project ->
        val preflight = rootProject.providers.gradleProperty("strata.paired.preflight").orNull
        val preflightRequest = preflight?.let { JsonSlurper().parse(File(it)) as Map<*, *> }
        if (preflightRequest != null && project.path == preflightRequest["project"]) {
            check(project.configurations.findByName("jmhRuntimeClasspath") != null)
            val templateName = rootProject.providers.gradleProperty("strata.paired.template").get()
            val template = project.tasks.named(templateName, JavaExec::class.java)
            project.tasks.register("strataPairedPreflight", JavaExec::class.java) {
                val source = template.get()
                source.copyTo(this)
                javaLauncher.set(source.javaLauncher)
                classpath = source.classpath
                check(preflightRequest["schema"] == "strata-paired-jvm-preflight-request-v1")
                mainClass.set(preflightRequest["main"].toString())
                setArgs(listOf(File(preflight).absolutePath))
                dependsOn(source.taskDependencies.getDependencies(source))
                outputs.upToDateWhen { false }
                outputs.cacheIf { false }
            }
        }
        val reportPath = rootProject.providers.gradleProperty("strata.paired.report").orNull
        val reportRequest = reportPath?.let { JsonSlurper().parse(File(it)) as Map<*, *> }
        if (reportRequest != null && project.path == reportRequest["project"]) {
            check(reportRequest.keys == setOf("schema", "project", "template", "kind", "main", "arguments", "inputs"))
            check(reportRequest["schema"] == "strata-paired-jvm-owner-request-v1")
            val source = project.tasks.named(reportRequest["template"].toString()).get()
            val arguments = reportRequest["arguments"] as List<*>
            check(arguments.size in 1..256 && arguments.all { it is String && it.length <= 4096 && it.contains('\u0000').not() })
            val main = reportRequest["main"].toString()
            check(main.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")))
            when (reportRequest["kind"]) {
                "java-exec" -> check(source is JavaExec)
                "test" -> check(source is Test)
                else -> error("Unsupported owner report template")
            }
            project.tasks.register("strataPairedEvidence", JavaExec::class.java) {
                when (source) {
                    is JavaExec -> {
                        source.copyTo(this)
                        classpath = source.classpath
                        javaLauncher.set(source.javaLauncher)
                    }
                    is Test -> {
                        classpath = source.classpath
                        javaLauncher.set(source.javaLauncher)
                        jvmArgs(source.jvmArgs.orEmpty().filter { it.startsWith("-Xmx").not() && it.startsWith("-XX:MaxMetaspaceSize=").not() })
                        systemProperties(source.systemProperties)
                        workingDir(source.workingDir)
                    }
                }
                mainClass.set(main)
                setArgs(arguments.map { it.toString() })
                dependsOn(source.taskDependencies.getDependencies(source))
                outputs.upToDateWhen { false }
                outputs.cacheIf { false }

            }
        }
        project.tasks.configureEach {
            javaClass.methods.filter {
                it.name in setOf("getWorkerJvmArgs", "getJvmArgs") && it.parameterCount == 0 && ListProperty::class.java.isAssignableFrom(it.returnType)
            }.forEach { getter ->
                val arguments = getter.invoke(this) as ListProperty<*>
                ListProperty::class.java.getMethod("addAll", Iterable::class.java)
                    .invoke(arguments, listOf("-Xmx512m", "-XX:MaxMetaspaceSize=256m") + workerDiagnostics)
                doFirst {
                    val actual = arguments.get().map { it.toString() }
                    check(actual.lastOrNull { it.startsWith("-Xmx") } == "-Xmx512m")
                    check(actual.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=256m")
                    check(actual.takeLast(workerDiagnostics.size) == workerDiagnostics)
                }
            }
        }
        project.tasks.withType<JavaCompile>().configureEach {
            options.forkOptions.memoryInitialSize = "64m"
            options.forkOptions.memoryMaximumSize = "512m"
            options.forkOptions.jvmArgs = options.forkOptions.jvmArgs.orEmpty() + listOf("-Xmx512m", "-XX:MaxMetaspaceSize=256m")
            doFirst {
                val actual = options.forkOptions.jvmArgs.orEmpty()
                check(options.forkOptions.memoryMaximumSize == "512m")
                check(actual.lastOrNull { it.startsWith("-Xmx") } == "-Xmx512m")
                check(actual.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=256m")
            }
        }
        project.tasks.withType<Test>().configureEach {
            minHeapSize = "64m"
            maxHeapSize = "512m"
            maxParallelForks = 1
            jvmArgs("-XX:MaxMetaspaceSize=256m")
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            doFirst {
                check(enabled && ignoreFailures.not())
                check(filter.includePatterns.isEmpty() && filter.excludePatterns.isEmpty())
                check(includes.isEmpty() && excludes.isEmpty())
                check(allJvmArgs.lastOrNull { it.startsWith("-Xmx") } == "-Xmx512m")
                check(allJvmArgs.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=256m")
                check(maxParallelForks == 1)
                if (rootProject.providers.gradleProperty("strata.paired.testClasspathTask").orNull == path) {
                    val retained = File(rootProject.providers.gradleProperty("strata.paired.preserveTestClasspath").get())
                    preserveOwnedClasspath(classpath.files, retained, gradle.taskGraph.allTasks)
                }
            }
        }
        project.tasks.withType<JavaExec>().configureEach {
            minHeapSize = "64m"
            maxHeapSize = "768m"
            val classLoads = File(checkNotNull(System.getenv("STRATA_PAIRED_CLASS_LOGS"))).canonicalFile
            check(classLoads.isDirectory)
            jvmArgs("-XX:MaxMetaspaceSize=384m")
            doFirst {
                val classLoadPath = File(classLoads, "classes-%p-%t.log").relativeTo(workingDir.canonicalFile).invariantSeparatorsPath
                jvmArgs("-Xlog:class+load=info:file=$classLoadPath:pid,level,tags:filecount=0")
                check(allJvmArgs.lastOrNull { it.startsWith("-Xmx") } == "-Xmx768m")
                check(allJvmArgs.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=384m")
                listOf("benchmarks", "workloads").forEach { name ->
                    rootProject.providers.gradleProperty("strata.performance.$name").orNull?.let {
                        systemProperty("strata.performance.$name", it)
                    }
                }
                listOf("parameters", "fixtureInputs").forEach { name ->
                    rootProject.providers.gradleProperty("strata.performance.$name").orNull?.let {
                        systemProperty("strata.performance.$name", rootProject.file(it).absolutePath)
                    }
                }
                rootProject.providers.gradleProperty("strata.paired.inputProperties").orNull?.let { selected ->
                    val inventory = JsonSlurper().parse(File(selected)) as Map<*, *>
                    check(inventory.keys == setOf("schema", "entries") && inventory["schema"] == "strata-paired-jvm-input-properties-v1")
                    val entries = inventory["entries"] as Map<*, *>
                    check(entries.size in 1..32)
                    val inputs = Properties()
                    rootProject.file(rootProject.providers.gradleProperty("strata.performance.fixtureInputs").get()).bufferedReader(Charsets.UTF_8).use(inputs::load)
                    val admitted = inputs.values.map { File(it.toString()).canonicalFile }.toSet()
                    entries.forEach { (property, value) ->
                        check(property is String && property.matches(Regex("strata\\.performance\\.[a-z][A-Za-z0-9]*Inventory")))
                        val binding = value as Map<*, *>
                        check(binding.keys == setOf("path", "sha256"))
                        val path = File(binding["path"].toString()).canonicalFile
                        check(path in admitted && path.isFile && preparedIdentity(path) == binding["sha256"])
                        systemProperty(property, path.absolutePath)
                        println("paired.jvm.inputProperty " + JsonOutput.toJson(mapOf("task" to this.path, "property" to property, "path" to path.invariantSeparatorsPath, "sha256" to binding["sha256"])))
                    }
                }
                rootProject.providers.gradleProperty("strata.performance.profilers").orNull?.let { requested ->
                    val profilers = requested.split(',')
                    check(profilers.isNotEmpty() && profilers.toSet().size == profilers.size)
                    check(profilers.all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) })
                    val forwarded = args.orEmpty().windowed(2).filter { it[0].contentEquals("-prof") }.map { it[1] }.toSet()
                    args(profilers.filter { it !in forwarded }.flatMap { listOf("-prof", it) })
                }
                val requested = rootProject.providers.gradleProperty("strata.paired.runtime").orNull
                if (requested != null && (project.configurations.findByName("jmhRuntimeClasspath") != null || name == "strataPairedEvidence")) {
                    val targets = Properties()
                    File(requested).bufferedReader(Charsets.UTF_8).use(targets::load)
                    val configuration = if (name == "strataPairedEvidence" && reportRequest?.get("kind") == "test") "testRuntimeClasspath" else "jmhRuntimeClasspath"
                    val artifacts = project.configurations.getByName(configuration).incoming.artifacts.artifacts
                        .filter { (it.id.componentIdentifier as? ProjectComponentIdentifier)?.projectPath in targets.stringPropertyNames() }
                    // One paired manifest can span suites with different compiled runtime dependencies.
                    val self = name == "strataPairedEvidence" && reportRequest?.get("kind") == "test" && project.path in targets.stringPropertyNames()
                    val selectedTargets = artifacts.map { (it.id.componentIdentifier as ProjectComponentIdentifier).projectPath }.toSet() + if (self) setOf(project.path) else emptySet()
                    check(selectedTargets.isNotEmpty())
                    val mainOutput = if (self) project.extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main").output.files.map { it.canonicalFile }.toSet() else emptySet()
                    val replaced = artifacts.map { it.file.canonicalFile }.toSet() + mainOutput
                    val archives = selectedTargets.sorted().map { File(targets.getProperty(it)).canonicalFile }
                    check(archives.toSet().size == selectedTargets.size && archives.all { it.isFile && it.extension == "jar" })
                    classpath = project.files(classpath.files.filter { it.canonicalFile !in replaced }) + project.files(archives)
                }
                rootProject.providers.gradleProperty("strata.performance.operationRequest").orNull?.let { request ->
                    if (name in setOf("jmhHistorical", "jmhComponents", "jmhRemote")) {
                        mainClass.set("dev.s7a.strata.performance.JvmOperationReports")
                        setArgs(listOf(rootProject.file(request).absolutePath))
                    }
                }
                if (rootProject.providers.gradleProperty("strata.paired.classpath").isPresent || rootProject.providers.gradleProperty("strata.paired.preserveClasspath").isPresent) {
                    check(classpath.files.all { it.exists() || it.extension.contentEquals("jar").not() })
                    classpath = project.files(classpath.files.filter { it.exists() })
                }
                rootProject.providers.gradleProperty("strata.paired.classpath").orNull?.let { requestedClasspath ->
                    val inventory = JsonSlurper().parse(File(requestedClasspath)) as Map<*, *>
                    check(inventory["schema"] == "strata-paired-jvm-classpath-v1")
                    val entries = inventory["entries"] as List<*>
                    val records = entries.map { it as Map<*, *> }
                    check(records.map { File(it["source"].toString()).canonicalFile } == classpath.files.map { it.canonicalFile })
                    records.forEach { record ->
                        check(preparedIdentity(File(record["source"].toString())) == record["sha256"])
                        check(preparedIdentity(File(record["path"].toString())) == record["sha256"])
                    }
                    classpath = project.files(records.map { File(it["path"].toString()) })
                }
                rootProject.providers.gradleProperty("strata.paired.preserveClasspath").orNull?.let { retainedPath ->
                    val entries = preservePreparedClasspath(classpath.files, File(retainedPath))
                    classpath = project.files(entries.map { File(it.getValue("path")) })
                }
                if (name == "strataPairedEvidence") {
                    val specification = checkNotNull(reportRequest)
                    val configuration = if (specification["kind"] == "test") "testRuntimeClasspath" else "jmhRuntimeClasspath"
                    val libraries = Properties()
                    project.configurations.getByName(configuration).incoming.artifacts.artifacts.forEach { artifact ->
                        val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                        if (module != null) {
                            val label = "${module.group}:${module.module}:${module.version}:${artifact.file.name}"
                            check(libraries.setProperty(label, artifact.file.absolutePath) == null)
                        }
                    }
                    check(libraries.isNotEmpty())
                    val inputs = File(specification["inputs"].toString())
                    inputs.parentFile.mkdirs()
                    inputs.bufferedWriter(Charsets.UTF_8).use { libraries.store(it, "Resolved non-Strata owner report libraries") }
                    systemProperty("strata.performance.inputs", inputs.absolutePath)
                }
                rootProject.providers.gradleProperty("strata.paired.sourceClasspaths").orNull?.let { output ->
                    val build = rootProject.projectDir.canonicalFile.relativeTo(workspace).invariantSeparatorsPath.ifEmpty { "root" }
                    val relative = "$build/${path.removePrefix(":").replace(':', '-')}/classpath.json"
                    val inventory = File(output, relative)
                    preserveOwnedClasspath(classpath.files, inventory.parentFile, gradle.taskGraph.allTasks)
                    logger.lifecycle("paired.jvm.sourceClasspath {}", JsonOutput.toJson(mapOf("task" to path, "inventory" to relative)))
                }
                logger.lifecycle("paired.jvm.origin {}", JsonOutput.toJson(mapOf("task" to path, "main" to mainClass.get(), "executable" to javaLauncher.get().executablePath.asFile.absolutePath, "classpath" to classpath.asPath)))
                if (name == "strataPairedEvidence" && rootProject.providers.gradleProperty("strata.paired.prepareClasspath").orNull?.toBooleanStrict() == true) {
                    // This opt-in source preparation freezes actual bytes but executes no report main or measurement.
                    throw StopExecutionException("Prepared owner report classpath only; no report invocation")
                }
            }
        }
    }
    if (rootProject.projectDir.canonicalFile == workspace) {
        rootProject.providers.gradleProperty("strata.paired.prepareTestClasspaths").orNull?.let { requested ->
            val paths = requested.split(',')
            check(paths.isNotEmpty() && paths.size <= 16_384 && paths.distinct().size == paths.size)
            paths.forEach { path ->
                check(path.matches(Regex(":[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)+")))
                val owner = rootProject.project(path.substringBeforeLast(':'))
                val gate = owner.tasks.named(path.substringAfterLast(':'), Test::class.java).get()
                check(gate.path == path)
                // Resolve libraries in bounded preparation; the unchanged full Test task runs only after all pins pass.
                logger.lifecycle("paired.test.preparedClasspath {} {}", path, gate.classpath.files.size)
            }
        }
    }
}
