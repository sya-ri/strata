import groovy.json.JsonSlurper
import java.io.StringWriter
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

tasks.register("qodanaKotlinModel") {
    group = "verification"
    description = "Retains source roots and Kotlin settings in every actual compiler context."
    notCompatibleWithConfigurationCache("Updates owned generated IDEA metadata from fresh compiler declarations.")
    dependsOn("idea", "qodanaDeclarationInventory")
    doLast {
        val inventory = JsonSlurper().parse(rootProject.file("build/qodana/declarations.json")) as Map<*, *>
        val projects = (inventory["projects"] as List<*>).map { it as Map<*, *> }.filter { it["iml"] != null }
        val absentOutputs = projects.flatMap { owner ->
            (owner["outputs"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == false }.map { rootProject.file(it["path"] as String).toPath().toAbsolutePath().normalize() }
        }.toSet()
        val roots = projects.flatMap { owner ->
            (owner["roots"] as List<*>).map { it as Map<*, *> }.filter { it["exists"] == true && it["generated"] == false }.map { root ->
                val kind = root["kind"] as String
                require(kind in setOf("Source", "TestSource", "Resource", "TestResource")) { "Unsupported compiler root kind: $kind" }
                AnalysisRoot(owner["module"] as String, owner["directory"] as String?, root["path"] as String,
                    kind.startsWith("Test"), kind.endsWith("Resource"))
            }
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
            roots.filter { it.module == module }.sortedBy { it.path }.forEach { source ->
                val url = "file://${rootProject.file(source.path).canonicalPath.replace(File.separatorChar, '/')}"
                val content = if (source.directory != null && source.path.startsWith("${source.directory}/")) primary else manager.element("content", mapOf("url" to url))
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
        logger.lifecycle("Retained ${roots.size} compiler roots in their declared IDEA contexts.")
    }
}
