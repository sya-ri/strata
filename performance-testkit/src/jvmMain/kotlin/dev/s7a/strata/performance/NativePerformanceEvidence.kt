package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URL
import java.nio.file.Files
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
        val cpuMetadata = cpuReport.objectField("strata")
        LoadedArtifactMetadata.verifyComplete(cpuMetadata)
        val cpuModules = cpuMetadata.arrayField("modules").map { it.asJsonObject }
        require(cpuModules.size == representatives.size && cpuModules.map { it.textField("representativeClass") }.toSet() == representatives) { "Incomplete or duplicate CPU module inventory" }
        return JsonArray().apply {
            modules.forEach { module ->
                require(module.textField("status").contentEquals("resolved")) { "Unresolved native artifact" }
                val path = verifyOrigin(module.objectField("codeSource"))
                val resource = module.objectField("classResource")
                val representative = module.textField("representativeClass")
                verifyResource(path, representative, resource)
                val receipt = JsonObject().apply { addProperty("module", module.textField("module")) }
                if (module.has("classTree")) {
                    val identity = cpuModules.single { it.textField("representativeClass").contentEquals(representative) }
                    val cpuPath = verifyOrigin(identity.objectField("codeSource"))
                    verifyResource(cpuPath, representative, identity.objectField("classResource"))
                    require(identity.objectField("classResource").get("sha256") == resource.get("sha256")) { "CPU/native representative mismatch" }
                    val tree = JvmEvidenceFiles.classTree(cpuPath)
                    require(tree == identity.objectField("classTree") && tree == module.objectField("classTree") && tree == JvmEvidenceFiles.classTree(path)) { "CPU/native class-tree mismatch" }
                    if (Files.isDirectory(cpuPath)) {
                        verifyOrigin(identity.objectField("codeSource"))
                        receipt.add("cpu_class_directory_sha256", identity.objectField("codeSource").get("sha256"))
                    } else {
                        receipt.addProperty("cpu_jar_sha256", ArtifactIdentity.file(cpuPath))
                    }
                    receipt.add("native_class_tree", tree)
                } else {
                    receipt.add(if (Files.isDirectory(path)) "class_directory_sha256" else "standalone_jar_sha256", module.objectField("codeSource").get("sha256"))
                }
                add(receipt)
            }
        }
    }

    private fun verifyOrigin(origin: JsonObject): Path {
        val uri = URI(origin.textField("url"))
        require(uri.scheme.contentEquals("file") && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) { "Expected a local artifact origin" }
        return Path.of(uri).also { path ->
            if (Files.isDirectory(path)) {
                require(ArtifactResolution.decode(origin.textField("status")) == ArtifactResolution.Available) { "Unavailable class-directory identity" }
                require(ArtifactLocationKind.decode(origin.textField("kind")) == ArtifactLocationKind.ClassDirectory) { "Expected a declared class-directory identity" }
                require(origin.textField("hashedUrl").contentEquals(origin.textField("url"))) { "Class-directory hash origin differs" }
                val tree = JvmEvidenceFiles.classTree(path)
                listOf("algorithm", "entryCount", "bytes", "sha256").forEach { field -> require(origin.get(field) == tree.get(field)) { "Class-directory identity differs: $field" } }
            } else {
                val kind = origin.get("kind")?.let { ArtifactLocationKind.decode(it.textValue()) } ?: ArtifactLocationKind.File
                require(kind == ArtifactLocationKind.File) { "Expected a file artifact identity" }
                JvmEvidenceFiles.verifyFile(path, origin.textField("sha256"))
            }
        }
    }

    private fun verifyResource(
        path: Path,
        representative: String,
        resource: JsonObject,
    ) {
        val name = "${representative.replace('.', '/')}.class"
        if (Files.isDirectory(path)) {
            require(ArtifactResolution.decode(resource.textField("status")) == ArtifactResolution.Available) { "Unavailable representative resource" }
            require(ArtifactLocationKind.decode(resource.textField("kind")) == ArtifactLocationKind.File && resource.textField("hashedUrl").contentEquals(resource.textField("url"))) { "Expected a direct class-directory resource" }
            val actual = JvmEvidenceFiles.classDirectoryResource(path, name, URL(resource.textField("url")))
            require(Files.size(actual) <= 8L * 1024 * 1024) { "Oversized representative resource" }
            JvmEvidenceFiles.verifyFile(actual, resource.textField("sha256"))
            return
        }
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
