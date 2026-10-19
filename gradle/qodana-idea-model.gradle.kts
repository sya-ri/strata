import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.StringWriter
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/** One source or resource root in its actual compiler context. */
data class AnalysisRoot(
    val module: String,
    val directory: String?,
    val path: String,
    val test: Boolean,
    val resource: Boolean,
    val inputs: Map<String, String>,
    val filtered: Boolean,
)

/** Returns exact direct children, avoiding nested or differently owned IDEA elements. */
fun Element.children(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == name }

/** Appends one ordinary IDEA element with declaration-derived attributes. */
fun Element.element(name: String, attributes: Map<String, String> = emptyMap()): Element =
    ownerDocument.createElement(name).also { child ->
        attributes.forEach { (key, value) -> child.setAttribute(key, value) }
        appendChild(child)
    }

/** Derives a stable analysis identity from immutable declaration text. */
fun analysisIdentity(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Identifies a resolved compiler installation independently of the requested bytecode target. */
fun compilerSdkName(toolchain: Map<*, *>): String {
    val identity = listOf("installation", "java", "runtimeVersion", "vendor").joinToString("\u0000") { toolchain[it].toString() }
    return "strata-compiler-${toolchain["java"]}-${analysisIdentity(identity).take(12)}"
}

val cleanQodanaSourceViews = tasks.register<Delete>("cleanQodanaSourceViews") {
    notCompatibleWithConfigurationCache("Recreates owned analysis source views for the selected revision.")
    val directory = rootProject.file("qodana-analysis-sources")
    delete(directory)
    isFollowSymlinks = false
    doFirst {
        require(directory.canonicalFile == rootProject.rootDir.canonicalFile.resolve("qodana-analysis-sources")) {
            "Refusing to replace an aliased analysis source-view directory: $directory"
        }
        require(providers.exec {
            workingDir(rootProject.rootDir)
            commandLine("git", "ls-files", "--", "qodana-analysis-sources")
        }.standardOutput.asText.get().isBlank()) { "Analysis source views must not replace tracked files." }
    }
}

tasks.register("qodanaKotlinModel") {
    group = "verification"
    description = "Retains source roots and Kotlin settings in every actual compiler context."
    notCompatibleWithConfigurationCache("Updates owned generated IDEA metadata from fresh compiler declarations.")
    dependsOn("idea", "qodanaDeclarationInventory", cleanQodanaSourceViews)
    doLast {
        val inventory = JsonSlurper().parse(rootProject.file("build/qodana/declarations.json")) as Map<*, *>
        val projects = (inventory["projects"] as List<*>).map { it as Map<*, *> }.filter { it["iml"] != null }
        val sdkDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val application = sdkDocument.createElement("application").also { sdkDocument.appendChild(it) }
        val sdkTable = application.element("component", mapOf("name" to "ProjectJdkTable"))
        val sdks = projects.mapNotNull { it["compilerToolchain"] as Map<*, *>? }
            .distinctBy { toolchain -> listOf("installation", "java", "runtimeVersion", "vendor").map { toolchain[it] } }
            .sortedBy { it["installation"].toString() }.map { toolchain ->
            val home = File(toolchain["installation"] as String).canonicalFile
            val executable = home.resolve("bin/java").takeIf(File::isFile) ?: home.resolve("bin/java.exe")
            require(home.isDirectory && executable.isFile) { "Compiler SDK is unavailable: $home" }
            val modules = providers.exec { commandLine(executable, "--list-modules") }.standardOutput.asText.get().lineSequence()
                .filter(String::isNotBlank).map { it.substringBefore('@') }.sorted().toList()
            require("java.base" in modules && modules.distinct().size == modules.size) { "Invalid compiler SDK modules: $home" }
            val name = compilerSdkName(toolchain)
            val sdk = sdkTable.element("jdk", mapOf("version" to "2"))
            listOf("name" to name, "type" to "JavaSDK", "version" to "java version \"${toolchain["runtimeVersion"]}\"", "homePath" to home.invariantSeparatorsPath).forEach { (tag, value) ->
                sdk.element(tag, mapOf("value" to value))
            }
            val roots = sdk.element("roots")
            val classes = roots.element("classPath").element("root", mapOf("type" to "composite"))
            val classRoots = modules.map { "jrt://${home.invariantSeparatorsPath}!/$it" }
            classRoots.forEach { classes.element("root", mapOf("type" to "simple", "url" to it)) }
            val sources = roots.element("sourcePath").element("root", mapOf("type" to "composite"))
            home.resolve("lib/src.zip").takeIf(File::isFile)?.let { sources.element("root", mapOf("type" to "simple", "url" to "jar://${it.invariantSeparatorsPath}!/")) }
            roots.element("javadocPath").element("root", mapOf("type" to "composite"))
            sdk.element("additional")
            mapOf("name" to name, "java" to toolchain["java"], "runtimeVersion" to toolchain["runtimeVersion"], "installation" to home.invariantSeparatorsPath, "roots" to classRoots)
        }
        require(sdks.map { it["name"] }.distinct().size == sdks.size) { "Compiler SDK identities collide." }
        val absentOutputs = projects.flatMap { owner ->
            (owner["outputs"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == false }.map { rootProject.file(it["path"] as String).toPath().toAbsolutePath().normalize() }
        }.toSet()
        val roots = projects.flatMap { owner ->
            (owner["roots"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == true && it["generated"] == false }.map { root ->
                val kind = root["kind"] as String
                require(kind in setOf("Source", "TestSource", "Resource", "TestResource")) { "Unsupported compiler root kind: $kind" }
                val files = root["files"] as Map<*, *>
                val inputs = (root["inputs"] as List<*>).associate { it as String to files[it] as String }
                AnalysisRoot(owner["module"] as String, owner["directory"] as String?, root["path"] as String,
                    kind.startsWith("Test"), kind.endsWith("Resource"), inputs, files.keys != inputs.keys)
            }
        }
        roots.filter { it.resource.not() }.groupBy { it.module }.forEach { (module, sources) ->
            val inputs = sources.flatMap { it.inputs.keys }
            require(inputs.distinct().size == inputs.size) { "Compiler source roots overlap within $module" }
        }
        val contexts = roots.filter { it.resource.not() }.groupBy { it.path }.mapValues { (_, sources) -> sources.map { it.module }.toSet() }
        val views = roots.filter { it.resource.not() && (it.filtered || 1 < contexts.getValue(it.path).size) }.associateWith { source ->
            "qodana-analysis-sources/${analysisIdentity(source.module).take(12)}/${analysisIdentity(source.path).take(16)}"
        }
        require(views.values.distinct().size == views.size) { "Analysis source-view identities collide." }
        val viewRecords = views.map { (source, path) ->
            val directory = rootProject.file(path)
            directory.mkdirs()
            val files = source.inputs.map { (input, expected) ->
                require(input.startsWith("${source.path}/")) { "Compiler input is outside its declared source root: $input" }
                val relative = input.removePrefix("${source.path}/")
                val bytes = rootProject.file(input).readBytes()
                require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == expected) { "Compiler source changed before analysis projection: $input" }
                directory.resolve(relative).apply { parentFile.mkdirs(); writeBytes(bytes) }
                relative to expected
            }.toMap()
            mapOf("module" to source.module, "path" to source.path, "view" to path, "kind" to if (source.test) "TestSource" else "Source", "files" to files)
        }
        rootProject.file("build/qodana/source-views.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("revision" to inventory["revision"], "roots" to viewRecords))) + "\n")
        projects.forEach { owner ->
            val module = owner["module"] as String
            val output = rootProject.file(owner["iml"] as String)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output)
            val root = document.documentElement
            val manager = root.children("component").single { it.getAttribute("name") == "NewModuleRootManager" }
            (owner["compilerToolchain"] as Map<*, *>?)?.let { toolchain ->
                val previous = manager.children("orderEntry").filter { it.getAttribute("type") in setOf("jdk", "inheritedJdk") }
                require(previous.size == 1) { "Expected one original SDK binding: $module" }
                manager.removeChild(previous.single())
                manager.element("orderEntry", mapOf("type" to "jdk", "jdkName" to compilerSdkName(toolchain), "jdkType" to "JavaSDK"))
            }
            val primary = manager.children("content").single()
            require(primary.getAttribute("url") == "file://\$MODULE_DIR\$/") { "Unexpected primary IDEA content: $module" }
            primary.children("sourceFolder").forEach { primary.removeChild(it) }
            roots.filter { it.module == module }.sortedBy { it.path }.forEach { source ->
                val path = views[source] ?: source.path
                val url = "file://${rootProject.file(path).canonicalPath.replace(File.separatorChar, '/')}"
                val content = if (source.directory != null && path.startsWith("${source.directory}/")) primary else manager.element("content", mapOf("url" to url))
                val kind = if (source.resource) "type" to if (source.test) "java-test-resource" else "java-resource" else "isTestSource" to source.test.toString()
                content.element("sourceFolder", mapOf("url" to url, kind))
            }
            // NO-SOURCE Java and resource tasks do not create every declared output directory.
            manager.children("orderEntry").filter { it.getAttribute("type") == "module-library" }.forEach { entry ->
                val classes = entry.children("library").flatMap { it.children("CLASSES") }.flatMap { it.children("root") }
                val discardedRoots = classes.filter { classesRoot ->
                    val original = classesRoot.getAttribute("url")
                    val url = original.removePrefix("jar://").removePrefix("file://").removeSuffix("!/")
                        .replace("\$PROJECT_DIR\$", rootDir.absolutePath.replace(File.separatorChar, '/'))
                        .replace("\$MODULE_DIR\$", output.parentFile.absolutePath.replace(File.separatorChar, '/'))
                    val path = File(url).toPath().toAbsolutePath().normalize()
                    if (original.startsWith("file://")) classesRoot.setAttribute("url", "file://${path.toFile().canonicalPath.replace(File.separatorChar, '/')}")
                    path in absentOutputs
                }
                discardedRoots.forEach { it.parentNode.removeChild(it) }
                if (discardedRoots.isNotEmpty() && entry.children("library").flatMap { it.children("CLASSES") }.all { it.children("root").isEmpty() }) manager.removeChild(entry)
            }
            val compiler = owner["compiler"] as Map<*, *>?
            if (compiler != null) {
                require(root.children("component").none { it.getAttribute("name") == "FacetManager" }) { "Recreate the owned IDEA model with cleanIdea before exporting Kotlin compiler settings." }
                val facets = root.element("component", mapOf("name" to "FacetManager"))
                val facet = facets.element("facet", mapOf("type" to "kotlin-language", "name" to "Kotlin"))
                val target = compiler["jvmTarget"] as String
                val configuration = facet.element("configuration", mapOf("version" to "3", "platform" to "JVM $target", "useProjectSettings" to "false"))
                val arguments = configuration.element("compilerArguments")
                arguments.element("option", mapOf("name" to "moduleName", "value" to compiler["moduleName"] as String))
                arguments.element("option", mapOf("name" to "jvmTarget", "value" to target))
                (compiler["explicitApi"] as String?)?.let { arguments.element("option", mapOf("name" to "explicitApi", "value" to it)) }
                val friends = (compiler["friends"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == true }.map { it["path"] as String }
                if (friends.isNotEmpty()) {
                    val array = arguments.element("option", mapOf("name" to "friendPaths")).element("array")
                    friends.distinct().sorted().forEach { array.element("option", mapOf("value" to it)) }
                }
            }
            val text = StringWriter()
            val transformer = TransformerFactory.newInstance().newTransformer()
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            transformer.transform(DOMSource(document), StreamResult(text))
            output.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$text\n")
        }
        val sdkOutput = rootProject.file("build/qodana/ide-config/options/jdk.table.xml")
        sdkOutput.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(sdkDocument), StreamResult(sdkOutput))
        val sdkBindings = projects.mapNotNull { owner ->
            (owner["compilerToolchain"] as Map<*, *>?)?.let { owner["module"] to compilerSdkName(it) }
        }.toMap()
        rootProject.file("build/qodana/compiler-sdks.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("revision" to inventory["revision"], "sdks" to sdks, "modules" to sdkBindings))) + "\n")
        logger.lifecycle("Retained ${roots.size} compiler roots in their declared IDEA contexts.")
    }
}
