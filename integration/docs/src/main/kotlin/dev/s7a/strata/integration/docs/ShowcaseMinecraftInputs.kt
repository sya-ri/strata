package dev.s7a.strata.integration.docs

import com.google.gson.JsonObject
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftArchiveFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftDirectoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftIndexedFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Validates the explicitly declared official 26.2 client, asset index, object directory, and repository fixtures.
 * The caller keeps these files stable for one load; construction verifies the manifest hashes before any image decoding.
 * Aggregate manifest and index hashes fence that load but are not receipt identities because Mojang may revise bytes mapped by unrelated existing entries at the same logical version.
 * This temporary owner holds no native objects or open streams and is not retained by the completed assets.
 */
internal class ShowcaseMinecraftInputs(
    private val clientJar: Path,
    private val assetIndex: Path,
    assetObjects: Path,
    private val versionManifest: Path,
    testResources: Path,
) {
    /**
     * Inclusive allocation and work ceilings shared by all input readers and decoders.
     */
    val limits = MinecraftFontLoadLimits()

    /**
     * Exact compiler-selected capabilities of the documented release, independent of any loaded game.
     */
    val compatibility =
        MinecraftFontCompatibility(
            rasterizer = MinecraftTrueTypeRasterizer.FreeType,
            packFormat = 88,
            providerFilters = true,
            packOverlays = true,
            packFormatMinor = 0,
            minorPackFormats = true,
            interleavedShadows = true,
            fractionalUnihexAdvance = true,
            rejectMalformedOverlayMetadata = true,
            bakedGlyphMetrics = true,
            saturatingCeil = true,
            preparedTextBounds = true,
        )

    /**
     * Reproducible font options, without implicit operating-system or Minecraft preferences.
     */
    val options = MinecraftFontOptions(uniform = false, japaneseVariants = false, rightToLeft = false)

    private val documentLimits = limits.copy(maxAssetBytes = limits.maxDocumentBytes)
    private val manifestBytes = ShowcaseAssetIntegrity.read(versionManifest, limits.maxDocumentBytes)
    private val manifest = validateManifest(ShowcaseAssetJson.document(manifestBytes, limits))
    private val clientHash = verifyDeclared(clientJar, manifest.getAsJsonObject("downloads").getAsJsonObject("client"), limits.maxArchiveBytes)
    private val indexHash = verifyDeclared(assetIndex, manifest.getAsJsonObject("assetIndex"), limits.maxDocumentBytes.toLong())
    private val client = ShowcaseAssetSource(MinecraftArchiveFontAssetSource(clientJar, "minecraft-client"))
    private val officialSources =
        listOf(
            ShowcaseAssetSource(MinecraftIndexedFontAssetSource(assetIndex, assetObjects, "minecraft-assets", limits), indexedHashes()),
            client,
        )
    private val fixtures = ShowcaseAssetSource(MinecraftDirectoryFontAssetSource(testResources, "showcase-fixtures"))
    private val sources = officialSources + fixtures

    init {
        validateClientVersion()
    }

    /**
     * Loads every referenced font file into an immutable snapshot and rejects any incomplete default-font graph.
     */
    fun fonts(): MinecraftFontSnapshot =
        MinecraftFontSnapshot.load(officialSources, compatibility, options, limits).also { snapshot ->
            require(snapshot.diagnostics.isEmpty()) { "Showcase font inputs have resource diagnostics: " + snapshot.diagnostics.joinToString() }
            require(ResourceId("minecraft", "default") in snapshot.fontIds) { "Showcase inputs do not provide the default Minecraft font." }
        }

    /**
     * Reads one selected resource from the explicit low-to-high priority source stack, without fallback after a read failure.
     * Metadata uses the tighter document ceiling; returned bytes belong to the caller.
     */
    fun read(
        id: ResourceId,
        metadata: Boolean = false,
    ): ByteArray? {
        val path = "assets/" + id.namespace + "/" + id.path + if (metadata) ".mcmeta" else ""
        val selectedLimits = if (metadata) documentLimits else limits
        return if (id == ShowcaseGuiAsset.CoalGenerator.id) {
            fixtures.read(path, selectedLimits)
        } else {
            officialSources.asReversed().firstNotNullOfOrNull { source -> source.read(path, selectedLimits) }
        }
    }

    /**
     * Fences a completed load against changed input files and returns only immutable logical SHA-256 identities that affect the generated showcase.
     * The returned values include the client, selection contract, enumerated logical path sets, and consumed resources, but exclude aggregate manifest and index hashes.
     * The aggregate files are still validated against each other and rehashed here so a concurrent mutation cannot be published.
     * Returned identities never contain local absolute paths and can be serialized into a portable generation receipt.
     */
    fun inputHashes(playerName: String): Map<String, String> {
        require(ShowcaseAssetIntegrity.sha256(ShowcaseAssetIntegrity.read(versionManifest, limits.maxDocumentBytes)) == ShowcaseAssetIntegrity.sha256(manifestBytes)) {
            "The declared showcase version manifest changed during loading."
        }
        require(ShowcaseAssetIntegrity.hashes(clientJar, limits.maxArchiveBytes).sha256 == clientHash) { "The declared showcase client changed during loading." }
        require(ShowcaseAssetIntegrity.hashes(assetIndex, limits.maxDocumentBytes.toLong()).sha256 == indexHash) { "The declared showcase index changed during loading." }
        sources.forEach { source -> source.verifyStable(limits) }
        val hashes =
            linkedMapOf(
                "client-jar" to clientHash,
                "selection" to ShowcaseAssetIntegrity.sha256((compatibility.toString() + "\n" + options + "\n" + ShowcaseGuiAsset.PlayerSkin.id + "\n" + playerName + "\n").toByteArray()),
            )
        sources.forEach { source -> hashes.putAll(source.hashes()) }
        return Collections.unmodifiableMap(hashes.toSortedMap())
    }

    private fun validateManifest(document: JsonObject): JsonObject {
        require(Release.entries.singleOrNull { release -> release.id == ShowcaseAssetJson.string(document.get("id")) } == Release.Minecraft262) {
            "The showcase version manifest must describe Minecraft 26.2."
        }
        val downloads = ShowcaseAssetJson.objectValue(document.get("downloads"))
        ShowcaseAssetJson.objectValue(downloads.get("client"))
        val index = ShowcaseAssetJson.objectValue(document.get("assetIndex"))
        require(ShowcaseAssetJson.string(index.get("id")).isNotEmpty()) { "The declared showcase asset index has no identifier." }
        return document
    }

    private fun verifyDeclared(
        path: Path,
        declaration: JsonObject,
        maximum: Long,
    ): String {
        val expected = ShowcaseAssetJson.string(declaration.get("sha1"))
        require(Regex("[a-f0-9]{40}").matches(expected)) { "The showcase manifest contains an invalid SHA-1." }
        require(Files.size(path) == ShowcaseAssetJson.integer(declaration.get("size")).toLong()) { "A declared showcase input has a different byte size." }
        val hashes = ShowcaseAssetIntegrity.hashes(path, maximum)
        require(hashes.sha1 == expected) { "A declared showcase input differs from its official manifest hash." }
        return hashes.sha256
    }

    private fun validateClientVersion() {
        val bytes = requireNotNull(client.read("version.json", documentLimits)) { "The declared client archive has no version.json." }
        val version = ShowcaseAssetJson.document(bytes, limits)
        require(Release.entries.singleOrNull { release -> release.id == ShowcaseAssetJson.string(version.get("id")) } == Release.Minecraft262) {
            "The declared client archive must contain Minecraft 26.2."
        }
        val pack = ShowcaseAssetJson.objectValue(version.get("pack_version"))
        val major = ShowcaseAssetJson.integer(pack.get("resource_major"))
        val minor = ShowcaseAssetJson.integer(pack.get("resource_minor"))
        require(major == compatibility.packFormat && minor == compatibility.packFormatMinor) { "The declared client has incompatible resource-pack capabilities." }
    }

    private fun indexedHashes(): Map<String, String> {
        val document = ShowcaseAssetJson.document(ShowcaseAssetIntegrity.read(assetIndex, limits.maxDocumentBytes), limits)
        val objects = ShowcaseAssetJson.objectValue(document.get("objects"))
        return objects.entrySet().associate { (path, value) ->
            val hash = ShowcaseAssetJson.string(ShowcaseAssetJson.objectValue(value).get("hash"))
            "assets/$path" to hash
        }
    }

    private enum class Release(
        val id: String,
    ) {
        Minecraft262("26.2"),
    }
}
