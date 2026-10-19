import groovy.json.JsonSlurper
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/** One actual compiler root competing for a single IDEA source owner. */
data class AnalysisRoot(
    val module: String,
    val project: String,
    val directory: String?,
    val path: String,
    val test: Boolean,
    val resource: Boolean,
    val native: Boolean,
    val inputs: Int,
) {
    /** Keeps a complete compilation together before choosing a physical non-native owner. */
    val physical: Boolean get() = native.not() && directory != null && path.startsWith("$directory/")
}

/** Returns exact direct children, avoiding nested or differently owned IDEA elements. */
fun Element.children(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == name }

/** Appends one ordinary IDEA element with declaration-derived attributes. */
fun Element.element(name: String, attributes: Map<String, String> = emptyMap()): Element =
    ownerDocument.createElement(name).also { child ->
        attributes.forEach { (key, value) -> child.setAttribute(key, value) }
        appendChild(child)
    }

tasks.register("qodanaKotlinModel") {
    group = "verification"
    description = "Assigns shared compiler roots once and retains each variant's real compiled implementation."
    notCompatibleWithConfigurationCache("Updates owned generated IDEA metadata from fresh compiler declarations.")
    dependsOn("idea", "qodanaDeclarationInventory")
    doLast {
        val inventory = JsonSlurper().parse(rootProject.file("build/qodana/declarations.json")) as Map<*, *>
        val projects = (inventory["projects"] as List<*>).map { it as Map<*, *> }.filter { it["iml"] != null }
        val roots = projects.flatMap { owner ->
            (owner["roots"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == true && it["generated"] == false }.map { root ->
                val kind = root["kind"] as String
                require(kind in setOf("Source", "TestSource", "Resource", "TestResource")) { "Unsupported compiler root kind: $kind" }
                AnalysisRoot(owner["module"] as String, owner["project"] as String, owner["directory"] as String?, root["path"] as String,
                    kind.startsWith("Test"), kind.endsWith("Resource"), owner["native"] as Boolean, (root["inputs"] as List<*>).size)
            }
        }
        val canonical = roots.groupBy { it.path }.mapValues { (_, candidates) ->
            candidates.minWith(compareBy<AnalysisRoot> { it.resource }.thenByDescending { it.inputs }.thenBy { it.test }.thenByDescending { it.physical }.thenBy { it.project })
        }
        projects.forEach { owner ->
            val module = owner["module"] as String
            val output = rootProject.file(owner["iml"] as String)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output)
            val root = document.documentElement
            val manager = root.children("component").single { it.getAttribute("name") == "NewModuleRootManager" }
            val primary = manager.children("content").single()
            require(primary.getAttribute("url") == "file://\$MODULE_DIR\$/") { "Unexpected primary IDEA content: $module" }
            primary.children("sourceFolder").forEach { primary.removeChild(it) }
            canonical.values.filter { it.module == module }.sortedBy { it.path }.forEach { source ->
                val url = "file://\$PROJECT_DIR\$/${source.path}"
                val content = if (source.directory != null && source.path.startsWith("${source.directory}/")) primary else manager.element("content", mapOf("url" to url))
                val kind = if (source.resource) "type" to if (source.test) "java-test-resource" else "java-resource" else "isTestSource" to source.test.toString()
                content.element("sourceFolder", mapOf("url" to url, kind))
            }
            val lostRoots = roots.any { it.module == module && canonical.getValue(it.path).module != module }
            val outputs = (owner["outputs"] as List<*>).map { it as Map<*, *> }
            val fallback = if (lostRoots) outputs.filter { it["exists"] == true && (it["files"] as Map<*, *>).isNotEmpty() } else emptyList()
            if (lostRoots) {
                val ownedPaths = outputs.map { rootProject.file(it["path"] as String).toPath().toAbsolutePath().normalize() }.toSet()
                // JMH exports main outputs under TEST; displaced production inputs need their actual COMPILE visibility.
                manager.children("orderEntry").filter { it.getAttribute("type") == "module-library" }.forEach { entry ->
                    val classes = entry.children("library").flatMap { it.children("CLASSES") }.flatMap { it.children("root") }
                    val ownedRoots = classes.filter { classesRoot ->
                        val url = classesRoot.getAttribute("url").removePrefix("jar://").removePrefix("file://").removeSuffix("!/")
                            .replace("\$PROJECT_DIR\$", rootDir.absolutePath.replace(File.separatorChar, '/'))
                            .replace("\$MODULE_DIR\$", output.parentFile.absolutePath.replace(File.separatorChar, '/'))
                        File(url).toPath().toAbsolutePath().normalize() in ownedPaths
                    }
                    ownedRoots.forEach { it.parentNode.removeChild(it) }
                    if (ownedRoots.isNotEmpty() && entry.children("library").flatMap { it.children("CLASSES") }.all { it.children("root").isEmpty() }) manager.removeChild(entry)
                }
            }
            fallback.forEach { compiled ->
                val scope = if (compiled["production"] == true) emptyMap() else mapOf("scope" to "TEST")
                val entry = manager.element("orderEntry", mapOf("type" to "module-library") + scope)
                val library = entry.element("library")
                library.element("CLASSES").element("root", mapOf("url" to "file://\$PROJECT_DIR\$/${compiled["path"]}/"))
                library.element("JAVADOC")
                library.element("SOURCES")
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
                val friends = (compiler["friends"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == true }.map { it["path"] as String } +
                    fallback.filter { it["classes"] == true }.map { rootProject.file(it["path"] as String).canonicalPath.replace(File.separatorChar, '/') }
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
        logger.lifecycle("Assigned ${canonical.size} physical compiler roots to unique IDEA owners without discarding compiled variants.")
    }
}
