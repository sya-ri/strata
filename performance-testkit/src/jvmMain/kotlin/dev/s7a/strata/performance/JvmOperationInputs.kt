package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * Binds actual consumed file mappings to the full preserved JMH input inventory.
 * Unknown, changed or missing inputs fail; old archive hashes never stand in for live consumed bytes.
 */
internal object JvmOperationInputs {
    /**
     * Resolves current control libraries, external fixture files and loaded control resources.
     */
    internal fun capture(
        adapters: List<JvmOperationReportAdapter>,
        expected: JsonObject,
    ): JsonObject {
        val manifests = JsonObject()
        listOf("inputs", "fixtureInputs").forEach { name ->
            System.getProperty("strata.performance.$name")?.let { manifests.add(name, manifest(Path.of(it))) }
        }
        require(manifests.has("inputs")) { "Missing resolved control input manifest" }
        val sources = paths(manifests.objectField("inputs").objectField("files")).toMutableMap()
        (listOf(JmhFixtureSelection.inputs()) + adapters.map { it.inputs }).forEach { declared ->
            declared.forEach { (label, path) ->
                val previous = sources.putIfAbsent(label, path)
                require(previous == null || previous.toAbsolutePath().normalize() == path.toAbsolutePath().normalize()) { "Ambiguous consumed operation input: $label" }
            }
        }
        return JsonObject().apply {
            add("files", bind(sources, expected))
            add("manifests", manifests)
        }
    }

    /**
     * Records exact current paths and bytes only when every declared input matches its preserved copy.
     */
    internal fun bind(
        sources: Map<String, Path>,
        expected: JsonObject,
    ): JsonObject {
        require(sources.keys == expected.keySet()) { "Consumed operation input inventory differs from original JMH inputs" }
        return JsonObject().apply {
            sources.toSortedMap().forEach { (label, source) ->
                val path = source.toAbsolutePath().normalize()
                val hash = expected.textField(label)
                JvmEvidenceFiles.verifyFile(path, hash)
                add(
                    label,
                    JsonObject().apply {
                        addProperty("path", path.toString())
                        addProperty("sha256", hash)
                    },
                )
            }
        }
    }

    /**
     * Revalidates the actual consumed bytes while processing in the preserved invocation workspace.
     */
    internal fun verify(
        sources: JsonObject,
        expected: JsonObject,
    ) {
        val files = sources.objectField("files")
        val paths = files.entrySet().associate { (label, value) -> label to Path.of(value.asJsonObject.textField("path")) }
        require(bind(paths, expected) == files) { "Changed operation input mapping or identity" }
        sources.objectField("manifests").entrySet().forEach { (_, value) ->
            require(manifest(Path.of(value.asJsonObject.textField("path"))) == value) { "Changed consumed operation input manifest" }
        }
    }

    private fun paths(files: JsonObject): Map<String, Path> = files.entrySet().associate { (label, value) -> label to Path.of(value.textValue()) }

    private fun manifest(path: Path): JsonObject =
        JsonObject().apply {
            addProperty("path", path.toAbsolutePath().normalize().toString())
            add(
                "files",
                JsonObject().apply { JvmPerformanceInputs.read(path).toSortedMap().forEach { (label, source) -> addProperty(label, source.toString()) } },
            )
        }
}
