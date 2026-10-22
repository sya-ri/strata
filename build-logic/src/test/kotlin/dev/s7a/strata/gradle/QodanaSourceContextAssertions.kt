package dev.s7a.strata.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Independently checks declared compiler contexts against the physical IDEA source and library roots.
 */
internal class QodanaSourceContextAssertions(
    private val directory: Path,
) {
    /**
     * Requires exact source selections, real directory containment and resolvable compiled library roots.
     */
    fun verify(projects: Collection<Map<*, *>>) {
        val contexts =
            projects
                .flatMap { project ->
                    declaredRoots(project).filter { rootKind(it).code }.map { it["path"] to sourceModule(project, it) }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, modules) -> modules.toSet() }
        projects.forEach { project ->
            val declared = declaredRoots(project)
            val modules = (listOf(project["module"] as String) + declared.map { sourceModule(project, it) }).distinct()
            val observed = modules.flatMap { observedRoots(project, it) }
            val expected =
                declared.map { source ->
                    val module = sourceModule(project, source)
                    Triple(module, sourceView(module, source, contexts).normalize(), rootKind(source))
                }
            assertEquals(expected.toSet(), observed.toSet(), "Every real compiler context must retain its declared roots.")
            assertEquals(observed.size, observed.toSet().size, "A compiler context must not repeat a root.")
        }
        assertTrue(Files.readString(directory.resolve("bench-two/jmh-resolved-bench-two.iml")).contains("name=\"friendPaths\""))
        assertLibraryOutputRoots(projects)
    }

    /**
     * Retains only authored roots that actually exist in the current compiler input.
     */
    private fun declaredRoots(project: Map<*, *>): List<Map<*, *>> =
        (
            objects(project["roots"]).filter { (it["kind"] as String).startsWith("Test").not() } +
                objects(project["testCompilations"]).flatMap { objects(it["roots"]) }
        ).filter { it["exists"] == true && it["generated"] == false }

    /**
     * Assigns common production roots to their independent refinement module.
     */
    private fun sourceModule(
        project: Map<*, *>,
        source: Map<*, *>,
    ): String {
        val key =
            when (rootKind(source)) {
                RootKind.Source -> "kotlinRoots"
                RootKind.Resource -> "resourceRoots"
                RootKind.TestSource, RootKind.TestResource -> return "${(source["sourceSets"] as List<*>).single()}-${project["module"]}"
            }
        val common = objects(project["commonSourceSets"]).filter { source["path"] in it[key] as List<*> }
        assertTrue(common.size <= 1, "The fixture uses distinct source directories for each refinement.")
        return common.singleOrNull()?.let { "${project["module"]}.${it["name"]}" } ?: project["module"] as String
    }

    /**
     * Resolves imported roots and requires their own content element to contain them physically.
     */
    private fun observedRoots(
        project: Map<*, *>,
        module: String,
    ): List<Triple<String, Path, RootKind>> {
        val primary = directory.resolve(project["iml"] as String)
        val iml = if (module == project["module"]) primary else primary.parent.resolve("$module.iml")
        val model = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(iml.toFile())
        return model.getElementsByTagName("sourceFolder").elements().map { folder ->
            val source = path(folder.getAttribute("url"), primary.parent)
            assertEquals("file://${source.toRealPath().toString().replace('\\', '/')}", folder.getAttribute("url"), "Source roots must use resolved IDEA URLs.")
            val content = path((folder.parentNode as Element).getAttribute("url"), primary.parent)
            assertTrue(source.startsWith(content), "Compiler source $source must belong to its direct content root $content.")
            val kind =
                mapOf("java-resource" to RootKind.Resource, "java-test-resource" to RootKind.TestResource)[folder.getAttribute("type")]
                    ?: if (folder.getAttribute("isTestSource").toBooleanStrict()) RootKind.TestSource else RootKind.Source
            Triple(module, source, kind)
        }
    }

    /**
     * Requires a materialized shared or filtered view to contain exactly the compiler-selected bytes.
     */
    private fun sourceView(
        module: String,
        source: Map<*, *>,
        contexts: Map<Any?, Set<String>>,
    ): Path {
        val original = source["path"] as String
        val inputs = (source["inputs"] as List<*>).map { it as String }
        val filtered = inputs.toSet() != (source["files"] as Map<*, *>).keys
        if (rootKind(source).code.not() || (filtered.not() && contexts.getValue(original).size <= 1)) return directory.resolve(original)
        val view = directory.resolve("qodana-analysis-sources/${identity(module, 12)}/${identity(original, 16)}")
        val files = Files.walk(view).use { paths -> paths.filter { Files.isRegularFile(it) }.map { view.relativize(it).toString().replace('\\', '/') }.toList() }
        assertEquals(inputs.map { it.removePrefix("$original/") }.toSet(), files.toSet(), "A source view must retain exactly the compiler-selected files.")
        inputs.forEach { input ->
            assertTrue(Files.readAllBytes(directory.resolve(input)).contentEquals(Files.readAllBytes(view.resolve(input.removePrefix("$original/")))), "Analysis source bytes must match the working compiler input: $input")
        }
        return view
    }

    /**
     * Requires compiled libraries to resolve directly while omitting declared outputs that were never produced.
     */
    private fun assertLibraryOutputRoots(projects: Collection<Map<*, *>>) {
        val missing =
            projects
                .flatMap { project ->
                    objects(project["outputs"]).filter { it["exists"] == false }.map { directory.resolve(it["path"] as String).normalize() }
                }.toSet()
        val unresolved =
            projects.flatMap { project ->
                val primary = directory.resolve(project["iml"] as String)
                val modelFiles = (listOf(project["module"] as String) + declaredRoots(project).map { sourceModule(project, it) }).distinct()
                modelFiles.flatMap { name ->
                    val modelFile = primary.resolveSibling("$name.iml")
                    val model = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(modelFile.toFile())
                    val urls =
                        model
                            .getElementsByTagName("CLASSES")
                            .elements()
                            .flatMap { it.getElementsByTagName("root").elements() }
                            .map { it.getAttribute("url") }
                    urls
                        .filter { it.startsWith("file://") }
                        .filter { it.contains($$"$PROJECT_DIR$") || it.endsWith("/") || path(it, modelFile.parent) in missing }
                        .map { "${project["module"]}: $it" }
                }
            }
        assertEquals(emptyList<String>(), unresolved, "IDE libraries must not retain unresolved output roots.")
    }

    /**
     * Expands the IDEA macros relative to the selected project and module.
     */
    private fun path(
        url: String,
        moduleDirectory: Path,
    ): Path = Path.of(url.removePrefix("file://").replace($$"$PROJECT_DIR$", directory.toString()).replace($$"$MODULE_DIR$", moduleDirectory.toString())).normalize()

    /**
     * Recomputes the stable source-view identity independently of the production exporter.
     */
    private fun identity(
        value: String,
        length: Int,
    ): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())).take(length)

    /**
     * Reads exported object lists without invoking the production parser.
     */
    private fun objects(value: Any?): List<Map<*, *>> = (value as List<*>).map { it as Map<*, *> }

    /**
     * Reads DOM element lists in document order.
     */
    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    /**
     * Decodes the finite source role at the exported JSON boundary.
     */
    private fun rootKind(source: Map<*, *>): RootKind = RootKind.valueOf(source["kind"] as String)

    /**
     * Differentiates compiler inputs from resource roots and keeps production and test roles distinct.
     */
    private enum class RootKind(
        val code: Boolean,
    ) {
        Source(true),
        TestSource(true),
        Resource(false),
        TestResource(false),
    }
}
