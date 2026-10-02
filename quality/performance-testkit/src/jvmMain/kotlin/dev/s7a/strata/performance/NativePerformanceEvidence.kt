package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Verifies actual CPU and native loaded class bytes without trusting archive names or versions.
 * The consumer supplies its expected module inventory; the kit owns file and class-tree verification.
 */
public object NativePerformanceEvidence {
    /**
     * Rejects remote origins, changed loaded files, missing modules and CPU/native class mismatches.
     */
    public fun verify(
        nativeReport: JsonObject,
        cpuReport: JsonObject,
        representatives: Set<String>,
        nativeOnlyModules: Set<String>,
    ): JsonArray {
        val modules = nativeReport.objectField("strata").arrayField("modules").map { it.asJsonObject }
        val names = modules.map { it.textField("module") }
        require(names.isNotEmpty() && names.toSet().size == names.size) { "Duplicate or missing native module" }
        require(modules.filter { it.has("classTree") }.map { it.textField("representativeClass") }.toSet() == representatives) { "Incomplete native class-tree inventory" }
        require(modules.filter { it.has("classTree").not() }.map { it.textField("module") }.toSet() == nativeOnlyModules) { "Incomplete native-only inventory" }
        return JsonArray().apply {
            modules.forEach { module ->
                require(module.textField("status").contentEquals("resolved")) { "Unresolved native artifact" }
                val path = verifyOrigin(module.objectField("codeSource"))
                val resource = module.objectField("classResource")
                val representative = module.textField("representativeClass")
                verifyResource(path, representative, resource)
                val receipt = JsonObject().apply { addProperty("module", module.textField("module")) }
                if (module.has("classTree")) {
                    val identity = cpuReport.objectField("strata_class_sha256").objectField(representative)
                    val cpuPath = verifyOrigin(identity.objectField("code_source"))
                    verifyResource(cpuPath, representative, identity.objectField("class_resource"))
                    require(identity.objectField("class_resource").get("sha256") == resource.get("sha256")) { "CPU/native representative mismatch" }
                    val tree = JvmEvidenceFiles.classTree(cpuPath)
                    require(tree == module.objectField("classTree") && tree == JvmEvidenceFiles.classTree(path)) { "CPU/native class-tree mismatch" }
                    receipt.addProperty("cpu_jar_sha256", ArtifactIdentity.file(cpuPath))
                    receipt.add("native_class_tree", tree)
                } else {
                    receipt.add("standalone_jar_sha256", module.objectField("codeSource").get("sha256"))
                }
                add(receipt)
            }
        }
    }

    private fun verifyOrigin(origin: JsonObject): Path {
        val uri = URI(origin.textField("url"))
        require(uri.scheme.contentEquals("file") && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) { "Expected a local artifact origin" }
        return Path.of(uri).also { JvmEvidenceFiles.verifyFile(it, origin.textField("sha256")) }
    }

    private fun verifyResource(
        path: Path,
        representative: String,
        resource: JsonObject,
    ) {
        val name = "${representative.replace('.', '/')}.class"
        require(resource.textField("entryName").contentEquals(name)) { "Unexpected representative resource" }
        val expected = resource.textField("sha256")
        JvmEvidenceFiles.validateHash(expected)
        ZipFile(path.toFile()).use { archive ->
            val entry = checkNotNull(archive.getEntry(name))
            require(0 <= entry.size && entry.size <= 8L * 1024 * 1024) { "Oversized representative resource" }
            val actual = archive.getInputStream(entry).use(EntryIdentity::sha256)
            require(actual.contentEquals(expected)) { "Loaded representative bytes changed" }
        }
    }
}
