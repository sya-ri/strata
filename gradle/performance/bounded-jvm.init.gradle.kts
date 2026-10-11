import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Properties

// Apply the retained finite JVM profile to main and included builds; no benchmark registration is added.
gradle.projectsEvaluated {
    val workspace = File(checkNotNull(System.getenv("STRATA_PAIRED_WORKSPACE"))).canonicalFile
    check(rootProject.projectDir.canonicalFile in setOf(workspace, File(workspace, "build-logic").canonicalFile))
    check(gradle.startParameter.maxWorkerCount == 1 && gradle.startParameter.isDryRun.not())
    check(rootProject.providers.gradleProperty("kotlin.compiler.execution.strategy").get() == "in-process")
    check(rootProject.providers.gradleProperty("detekt.use.worker.api").get() == "false")
    check(Runtime.getRuntime().maxMemory() <= 768L * 1024L * 1024L)
    check(ManagementFactory.getRuntimeMXBean().inputArguments.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=1g")
    rootProject.allprojects.forEach { project ->
        project.tasks.configureEach {
            javaClass.methods.filter {
                it.name in setOf("getWorkerJvmArgs", "getJvmArgs") && it.parameterCount == 0 && ListProperty::class.java.isAssignableFrom(it.returnType)
            }.forEach { getter ->
                val arguments = getter.invoke(this) as ListProperty<*>
                ListProperty::class.java.getMethod("addAll", Iterable::class.java)
                    .invoke(arguments, listOf("-Xmx512m", "-XX:MaxMetaspaceSize=256m"))
                doFirst {
                    val actual = arguments.get().map { it.toString() }
                    check(actual.lastOrNull { it.startsWith("-Xmx") } == "-Xmx512m")
                    check(actual.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=256m")
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
            }
        }
        project.tasks.withType<JavaExec>().configureEach {
            minHeapSize = "64m"
            maxHeapSize = "768m"
            jvmArgs("-XX:MaxMetaspaceSize=384m", "-Xlog:class+load=info:stdout:pid,level,tags")
            doFirst {
                check(allJvmArgs.lastOrNull { it.startsWith("-Xmx") } == "-Xmx768m")
                check(allJvmArgs.lastOrNull { it.startsWith("-XX:MaxMetaspaceSize=") } == "-XX:MaxMetaspaceSize=384m")
                listOf("parameters", "fixtureInputs").forEach { name ->
                    rootProject.providers.gradleProperty("strata.performance.$name").orNull?.let {
                        systemProperty("strata.performance.$name", rootProject.file(it).absolutePath)
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
                if (requested != null && project.configurations.findByName("jmhRuntimeClasspath") != null) {
                    val targets = Properties()
                    File(requested).bufferedReader(Charsets.UTF_8).use(targets::load)
                    val artifacts = project.configurations.getByName("jmhRuntimeClasspath").incoming.artifacts.artifacts
                        .filter { (it.id.componentIdentifier as? ProjectComponentIdentifier)?.projectPath in targets.stringPropertyNames() }
                    check(artifacts.map { (it.id.componentIdentifier as ProjectComponentIdentifier).projectPath }.toSet() == targets.stringPropertyNames())
                    val replaced = artifacts.map { it.file.canonicalFile }.toSet()
                    val archives = targets.stringPropertyNames().sorted().map { File(targets.getProperty(it)).canonicalFile }
                    check(archives.toSet().size == targets.size && archives.all { it.isFile && it.extension == "jar" })
                    classpath = project.files(classpath.files.filter { it.canonicalFile !in replaced }) + project.files(archives)
                }
                rootProject.providers.gradleProperty("strata.performance.operationRequest").orNull?.let { request ->
                    if (name in setOf("jmhHistorical", "jmhComponents", "jmhRemote")) {
                        mainClass.set("dev.s7a.strata.performance.JvmOperationReports")
                        setArgs(listOf(rootProject.file(request).absolutePath))
                    }
                }
                logger.lifecycle("paired.jvm.origin {}", JsonOutput.toJson(mapOf("task" to path, "main" to mainClass.get(), "executable" to javaLauncher.get().executablePath.asFile.absolutePath, "classpath" to classpath.asPath)))
            }
        }
    }
}
