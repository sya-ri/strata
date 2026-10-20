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
import org.w3c.dom.Document

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

/** Projects real production source-set refinements without creating additional Gradle projects. */
fun analysisOwners(owner: Map<*, *>): List<Map<*, *>> {
    val declarations = (owner["commonSourceSets"] as? List<*>).orEmpty().map { it as Map<*, *> }
    val sources = (owner["roots"] as List<*>).map { it as Map<*, *> }
    val nodes = declarations.associateBy { it["name"] as String }
    require(nodes.size == declarations.size) { "Duplicate common source-set declaration: ${owner["module"]}" }
    val assigned = declarations.associate { declaration ->
        val sourcePaths = (declaration["kotlinRoots"] as List<*>).toSet()
        val resourcePaths = (declaration["resourceRoots"] as List<*>).toSet()
        val kotlinInputs = (declaration["kotlinInputs"] as List<*>).toSet()
        val resourceInputs = (declaration["resourceInputs"] as List<*>).toSet()
        declaration["name"] as String to sources.filter { source ->
            (source["kind"] == "Source" && source["path"] in sourcePaths) || (source["kind"] == "Resource" && source["path"] in resourcePaths)
        }.map { source ->
            val selected = if (source["kind"] == "Source") kotlinInputs else resourceInputs
            source + mapOf("inputs" to (source["inputs"] as List<*>).filter { it in selected })
        }
    }
    val active = assigned.filterValues { roots -> roots.any { it["exists"] == true && it["generated"] == false && (it["inputs"] as List<*>).isNotEmpty() } }.keys
    val reachable = mutableSetOf<String>()
    fun refinements(names: List<*>, ancestors: Set<String> = emptySet()): List<String> = names.flatMap { value ->
        val name = value as String
        require(name in nodes && name !in ancestors) { "Unknown or cyclic common source-set refinement: $name" }
        reachable.add(name)
        val dependencies = nodes.getValue(name)["dependsOn"] as List<*>
        if (name in active) {
            refinements(dependencies, ancestors + name)
            listOf(name)
        } else refinements(dependencies, ancestors + name)
    }.distinct().sorted()
    val moved = assigned.values.flatten().groupBy { it["path"] }.mapValues { (_, roots) -> roots.flatMap { it["inputs"] as List<*> }.toSet() }
    val retained = sources.mapNotNull { source ->
        if (source["kind"] !in setOf("Source", "Resource") || source["path"] !in moved) source else {
            val inputs = (source["inputs"] as List<*>).filter { it !in moved.getValue(source["path"]) }
            if (inputs.isEmpty()) null else source + mapOf("inputs" to inputs)
        }
    }
    val primaryRefinements = refinements((owner["sourceSetDependencies"] as? List<*>).orEmpty())
    require(reachable == nodes.keys) { "Common source sets differ from the JVM compilation refinement graph: ${owner["module"]}" }
    val original = owner + mapOf("roots" to retained, "common" to false, "templateIml" to owner["iml"],
        "sourceSet" to owner["defaultSourceSet"], "refines" to primaryRefinements)
    val compilation = if (declarations.isEmpty()) null else (owner["productionCompilations"] as List<*>).map { it as Map<*, *> }
        .single { it["sourceSet"] == owner["defaultSourceSet"] }
    val fragments = declarations.filter { it["name"] in active }.map { declaration ->
        val name = declaration["name"] as String
        require(name !in setOf(".", "..") && name.none { it in "/\\:" }) { "Noncanonical common source-set name: $name" }
        val module = "${owner["module"]}.$name"
        val iml = File(owner["iml"] as String).resolveSibling("$module.iml").invariantSeparatorsPath
        val compiler = owner["compiler"] as Map<*, *>
        owner + mapOf("module" to module, "iml" to iml, "templateIml" to owner["iml"], "roots" to assigned.getValue(name),
            "common" to true, "sourceSet" to name, "refines" to refinements(declaration["dependsOn"] as List<*>),
            "jvmCompilation" to compilation,
            "compiler" to (compiler + mapOf("friends" to emptyList<Any>())))
    }
    return listOf(original) + fragments
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
        val configured = (inventory["projects"] as List<*>).map { it as Map<*, *> }.filter { it["iml"] != null }
        val templates = configured.associate { owner ->
            owner["iml"] as String to DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rootProject.file(owner["iml"] as String))
        }
        val projects = configured.flatMap(::analysisOwners)
        require(projects.map { it["module"] }.distinct().size == projects.size && projects.map { it["iml"] }.distinct().size == projects.size) { "Analysis source-set module identities collide." }
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
            val document = templates.getValue(owner["templateIml"] as String).cloneNode(true) as Document
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
            val common = owner["common"] == true
            if (common) manager.removeChild(primary)
            if (common) {
                manager.children("orderEntry").filter { it.getAttribute("type") in setOf("module", "module-library") }.forEach { manager.removeChild(it) }
                val compilation = owner["jvmCompilation"] as Map<*, *>
                val classpaths = compilation["classpaths"] as Map<*, *>
                val dependencies = compilation["dependencies"] as Map<*, *>
                val compile = (dependencies[classpaths["compile"]] as List<*>).map { it as Map<*, *> }
                val runtime = (dependencies[classpaths["runtime"]] as List<*>).map { it as Map<*, *> }
                (compile + runtime).distinct().forEach { dependency ->
                    val scope = when {
                        dependency in compile && dependency in runtime -> emptyMap()
                        dependency in compile -> mapOf("scope" to "PROVIDED")
                        else -> mapOf("scope" to "RUNTIME")
                    }
                    when (dependency["kind"]) {
                        "Module" -> manager.element("orderEntry", mapOf("type" to "module", "module-name" to dependency["identity"] as String) + scope)
                        "Library" -> {
                            val file = File(dependency["path"] as String).canonicalFile
                            require(file.exists()) { "Common compiler library is unavailable: $file" }
                            val library = manager.element("orderEntry", mapOf("type" to "module-library") + scope).element("library")
                            val url = if (file.isDirectory) "file://${file.invariantSeparatorsPath}" else "jar://${file.invariantSeparatorsPath}!/"
                            library.element("CLASSES").element("root", mapOf("url" to url))
                            library.element("JAVADOC")
                            library.element("SOURCES")
                        }
                        else -> error("Unsupported common compiler dependency: $dependency")
                    }
                }
            }
            roots.filter { it.module == module }.sortedBy { it.path }.forEach { source ->
                val path = views[source] ?: source.path
                val url = "file://${rootProject.file(path).canonicalPath.replace(File.separatorChar, '/')}"
                val content = if (common.not() && source.directory != null && path.startsWith("${source.directory}/")) primary else manager.element("content", mapOf("url" to url))
                val kind = if (source.resource) "type" to if (source.test) "java-test-resource" else "java-resource" else "isTestSource" to source.test.toString()
                content.element("sourceFolder", mapOf("url" to url, kind))
            }
            // NO-SOURCE Java and resource tasks do not create every declared output directory.
            val ownedOutputs = (owner["outputs"] as List<*>).map { declared -> rootProject.file((declared as Map<*, *>)["path"] as String).toPath().toAbsolutePath().normalize() }.toSet()
            manager.children("orderEntry").filter { it.getAttribute("type") == "module-library" }.forEach { entry ->
                val classes = entry.children("library").flatMap { it.children("CLASSES") }.flatMap { it.children("root") }
                val discardedRoots = classes.filter { classesRoot ->
                    val original = classesRoot.getAttribute("url")
                    val url = original.removePrefix("jar://").removePrefix("file://").removeSuffix("!/")
                        .replace("\$PROJECT_DIR\$", rootDir.absolutePath.replace(File.separatorChar, '/'))
                        .replace("\$MODULE_DIR\$", output.parentFile.absolutePath.replace(File.separatorChar, '/'))
                    val path = File(url).toPath().toAbsolutePath().normalize()
                    if (original.startsWith("file://")) classesRoot.setAttribute("url", "file://${path.toFile().canonicalPath.replace(File.separatorChar, '/')}")
                    path in absentOutputs || path in ownedOutputs
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
                val refines = owner["refines"] as List<*>
                val multiplatform = common || refines.isNotEmpty()
                val configuration = facet.element("configuration", mapOf("version" to "3", "platform" to "JVM $target", "useProjectSettings" to "false"))
                if (multiplatform) {
                    configuration.setAttribute("isHmppProject", "true")
                    configuration.setAttribute("externalProjectId", owner["project"] as String)
                    configuration.element("newMppModelJpsModuleKind").textContent = if (common) "SOURCE_SET_HOLDER" else "COMPILATION_AND_SOURCE_SET_HOLDER"
                    configuration.element("sourceSets").element("sourceSet").textContent = owner["sourceSet"] as String
                    if (refines.isNotEmpty()) {
                        val dependencies = configuration.element("dependsOnModuleNames")
                        val base = configured.single { it["project"] == owner["project"] }["module"]
                        refines.forEach { name ->
                            val dependency = "$base.$name"
                            dependencies.element("dependsOn").textContent = dependency
                            manager.element("orderEntry", mapOf("type" to "module", "module-name" to dependency, "exported" to ""))
                        }
                    }
                }
                val arguments = configuration.element("compilerArguments")
                arguments.element("option", mapOf("name" to "moduleName", "value" to compiler["moduleName"] as String))
                arguments.element("option", mapOf("name" to "jvmTarget", "value" to target))
                if (multiplatform) arguments.element("option", mapOf("name" to "multiPlatform", "value" to "true"))
                if (compiler["expectActualClasses"] == true) arguments.element("option", mapOf("name" to "expectActualClasses", "value" to "true"))
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
        val projectOutput = rootProject.tasks.named("ideaProject").get().outputs.files.singleFile
        val projectDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(projectOutput)
        val moduleTable = projectDocument.documentElement.children("component").single { it.getAttribute("name") == "ProjectModuleManager" }.children("modules").single()
        projects.filter { it["common"] == true }.forEach { owner ->
            val path = "\$PROJECT_DIR\$/${owner["iml"]}"
            moduleTable.element("module", mapOf("fileurl" to "file://$path", "filepath" to path))
        }
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(projectDocument), StreamResult(projectOutput))
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
