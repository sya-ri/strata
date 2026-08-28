package dev.s7a.strata.runtime.minecraft.font

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks default ceilings against the reproducible official-resource measurements without loading game assets.
 * The receipt contains hashes and numeric observations only; native and adversarial TTF limits have separate tests.
 */
internal class MinecraftVanillaFontBudgetTest {
    @Test
    fun defaultsAccommodateEveryRecordedVanillaStackAndKeepItsProvenancePortable() {
        val receipt = receipt()
        assertEquals(1, receipt.get("schemaVersion").asInt)
        val records = receipt.getAsJsonArray("results").map { it.asJsonObject }
        val expectedVersions = checkNotNull(System.getProperty("strata.minecraftTargetVersions")).split(',')
        assertEquals(expectedVersions, records.map { it.get("version").asString })
        val limits = MinecraftFontLoadLimits()
        for (record in records) {
            val version = record.get("version").asString
            val source = record.getAsJsonObject("sources")
            val summary = record.getAsJsonObject("summary")
            for (name in listOf("metadata", "client", "assetIndex")) {
                val artifact = record.getAsJsonObject(name)
                assertTrue(artifact.get("sha1").asString.matches(Regex("[0-9a-f]{40}")), version)
                assertTrue(artifact.get("sha256").asString.matches(Regex("[0-9a-f]{64}")), version)
                val locator = artifact.get("locator").asString
                assertFalse(locator.startsWith("/") || locator.contains(":") || locator.contains("\\"), version)
            }
            ceiling(record.getAsJsonObject("client").get("bytes").asLong, limits.maxArchiveBytes, version)
            ceiling(source.number("clientJarEntries"), limits.maxSourceEntries.toLong(), version)
            ceiling(source.number("indexedAssetEntries"), limits.maxSourceEntries.toLong(), version)
            ceiling(source.number("clientJarEntries") + source.number("indexedAssetEntries") + summary.number("unihexFiles") * summary.number("maxUnihexArchiveEntries"), limits.maxEntries.toLong(), version)
            ceiling(maxOf(source.number("clientJarMaxEntryPathLength"), source.number("indexedAssetMaxPathLength"), summary.number("maxUnihexEntryPathLength")), limits.maxPathLength.toLong(), version)
            ceiling(maxOf(summary.number("maxFontJsonBytes"), source.number("indexedJsonBytes")), limits.maxDocumentBytes.toLong(), version)
            ceiling(summary.number("maxReferencedAssetBytes"), limits.maxAssetBytes.toLong(), version)
            ceiling(summary.number("referencedAssetBytes") + summary.number("fontJsonBytes") + source.number("indexedJsonBytes"), limits.maxInputBytes, version)
            ceiling(summary.number("fontDocumentInstances"), limits.maxFontDocuments.toLong(), version)
            ceiling(summary.number("providers"), limits.maxProviders.toLong(), version)
            ceiling(summary.number("maxExpandedProvidersPerFont") * summary.number("uniqueFontIds"), limits.maxResolvedProviders.toLong(), version)
            ceiling(summary.number("maxUnihexExpandedEntryBytes"), limits.maxDecompressedEntryBytes, version)
            ceiling(summary.number("unihexExpandedBytes") + summary.number("allPngArgbBytes"), limits.maxDecompressedBytes, version)
            ceiling(summary.number("unihexRecords") + summary.number("maxBitmapCells") * summary.number("providers"), limits.maxGlyphs.toLong(), version)
            ceiling(summary.number("unihexStrataLongRowBytes"), limits.maxGlyphRowBytes, version)
            limits.requireBitmapSheetSize(summary.get("maxPngWidth").asInt, summary.get("maxPngHeight").asInt)
            assertEquals(0L, summary.number("trueTypeFiles"), "TTF ceilings must not be attributed to vanilla measurements: $version")
        }
    }

    @Test
    fun maximaAreDerivedFromTheRecordedVersionsRatherThanHandWrittenEstimates() {
        val receipt = receipt()
        val records = receipt.getAsJsonArray("results").map { it.asJsonObject }
        for ((key, element) in receipt.getAsJsonObject("maxima").entrySet()) {
            val parts = key.split('.', limit = 2)
            val values = records.associate { it.get("version").asString to it.getAsJsonObject(parts[0]).number(parts[1]) }
            val maximum = values.values.max()
            assertEquals(maximum, element.asJsonObject.number("value"), key)
            assertEquals(values.filterValues { it == maximum }.keys.toList(), element.asJsonObject.getAsJsonArray("versions").map { it.asString }, key)
        }
    }

    private fun receipt(): JsonObject = checkNotNull(javaClass.getResourceAsStream("/font-evidence/vanilla-load-budgets.json")).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }

    private fun ceiling(
        observed: Long,
        maximum: Long,
        version: String,
    ) {
        assertTrue(observed <= maximum, "$version observed $observed exceeds the selected default ceiling $maximum")
    }

    private fun JsonObject.number(key: String): Long = get(key).asLong
}
