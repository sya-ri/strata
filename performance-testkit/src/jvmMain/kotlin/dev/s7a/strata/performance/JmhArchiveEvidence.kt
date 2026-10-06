package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Verifies preserved targets, representatives, class trees and fixture inputs from actual archive bytes.
 */
internal object JmhArchiveEvidence {
    /**
     * Returns exact target identities only after validating every registered module archive.
     */
    internal fun targets(
        directory: Path,
        receipt: JsonObject,
    ): JsonObject {
        val modules = receipt.objectField("runtime_metadata").arrayField("modules")
        val archives = receipt.objectField("target_archives")
        val names = modules.map { it.asJsonObject.textField("module") }
        require(names.isNotEmpty() && names.toSet().size == names.size && names.toSet() == archives.keySet()) { "Duplicate or unregistered JMH target" }
        return JsonObject().apply {
            modules.forEach { entry ->
                val module = entry.asJsonObject
                val name = module.textField("module")
                val path = JvmEvidenceFiles.preservedPath(directory, archives.textField(name))
                val origin = module.objectField("codeSource")
                val resource = module.objectField("classResource")
                val representative = module.textField("representativeClass")
                require(module.textField("status").contentEquals("resolved") && origin.textField("status").contentEquals("available") && resource.textField("status").contentEquals("available")) { "Unresolved JMH target" }
                JvmEvidenceFiles.verifyFile(path, origin.textField("sha256"))
                require(JvmEvidenceFiles.classTree(path) == module.objectField("classTree")) { "Preserved JMH class tree differs" }
                verifyRepresentative(path, representative, resource)
                add(
                    name,
                    JsonObject().apply {
                        addProperty("representative", representative)
                        add("archive", origin.get("sha256"))
                        add("resource", resource.get("sha256"))
                        add("classTree", module.get("classTree"))
                    },
                )
            }
        }
    }

    /**
     * Returns exact logical input digests after bounded validation of unique preserved files.
     */
    internal fun inputs(
        directory: Path,
        receipt: JsonObject,
    ): JsonObject {
        val inputs = receipt.objectField("inputs")
        require(inputs.size() <= 16_384 && inputs.keySet().all(String::isNotBlank))
        val seen = mutableSetOf<String>()
        var total = 0L
        return JsonObject().apply {
            inputs.entrySet().sortedBy { it.key }.forEach { (name, value) ->
                val entry = value.asJsonObject
                val filename = entry.textField("archive")
                require(Regex("input-[0-9]+\\.bin").matches(filename) && seen.add(filename)) { "Unsafe or duplicate JMH fixture input archive" }
                val path = JvmEvidenceFiles.preservedPath(directory, filename)
                total = Math.addExact(total, Files.size(path))
                require(total <= 64L * 1024 * 1024) { "Oversized preserved JMH fixture inputs" }
                val hash = entry.textField("sha256")
                JvmEvidenceFiles.verifyFile(path, hash)
                addProperty(name, hash)
            }
        }
    }

    private fun verifyRepresentative(
        path: Path,
        name: String,
        resource: JsonObject,
    ) {
        val expectedEntry = "${name.replace('.', '/')}.class"
        require(resource.textField("entryName").contentEquals(expectedEntry)) { "Unexpected JMH representative resource" }
        JvmEvidenceFiles.validateHash(resource.textField("sha256"))
        ZipFile(path.toFile()).use { archive ->
            val entry = checkNotNull(archive.getEntry(expectedEntry))
            val hash = archive.getInputStream(entry).use(EntryIdentity::sha256)
            require(hash.contentEquals(resource.textField("sha256"))) { "Preserved JMH representative differs" }
        }
    }
}
