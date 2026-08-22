package dev.s7a.strata.runtime.minecraft.fabric

import com.google.gson.JsonParseException
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Verifies strict selection and decoding of the supported bitmap-font provider graph.
 */
internal class FabricMinecraftFontContractTest {
    @Test
    fun acceptsAccentedHeightTwelveBeforeTheAsciiProvider() {
        val reads = ArrayList<Identifier>()
        val documents = validDocuments()
        assertDoesNotThrow {
            validateMinecraftRegularFontContract(asciiImage()) { identifier ->
                reads.add(identifier)
                documents.getValue(identifier)
            }
        }
        assertEquals(listOf(defaultIdentifier, spaceIdentifier, includeDefaultIdentifier), reads)
    }

    @Test
    fun incompleteAsciiCoverageDoesNotSkipLaterFallbackProviders() {
        val readFailure = IOException("unifont must be visited")
        val documents =
            validDocuments() +
                (includeDefaultIdentifier to validDocuments().getValue(includeDefaultIdentifier).replace("\\u0021", "\\u0000"))

        val escaped =
            assertThrows(IOException::class.java) {
                validateMinecraftRegularFontContract(asciiImage()) { identifier ->
                    if (identifier == unifontIdentifier) throw readFailure
                    documents.getValue(identifier)
                }
            }
        assertSame(readFailure, escaped)
    }

    @Test
    fun validatesAsciiMetricsRowsAndCellMappingExactly() {
        val valid = validDocuments()
        val include = valid.getValue(includeDefaultIdentifier)
        assertDoesNotThrow {
            validateMinecraftRegularFontContract(asciiImage(), reader(valid))
        }
        listOf(
            include.replace("\"file\":\"minecraft:font/ascii.png\",", "\"file\":\"minecraft:font/ascii.png\",\"height\":7,"),
            include.replace("\"ascent\":7,", ""),
            include.replace("\"chars\":[$asciiRows]", "\"chars\":[${asciiRows.substringBeforeLast(',')}]"),
            include.replace("\\u000f", ""),
            include.replace("\\u0021", "\\u0022"),
        ).forEach { invalidInclude ->
            assertInvalid(valid + (includeDefaultIdentifier to invalidInclude))
        }
    }

    @Test
    fun validatesSpaceAndProviderIdentityBeforeCompletion() {
        val valid = validDocuments()
        val space = valid.getValue(spaceIdentifier)
        val include = valid.getValue(includeDefaultIdentifier)
        val incompleteRows = asciiRows.replace("\\u0021", "\\u0000")
        val duplicateAscii =
            """{"providers":[{"type":"bitmap","file":"minecraft:font/accented.png","height":12,"ascent":10,"chars":["\u00c0"]},{"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":[$incompleteRows]},{"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":[$asciiRows]}]}"""
        listOf(
            valid + (spaceIdentifier to space.replace("\" \":4", "\" \":5")),
            valid + (spaceIdentifier to space.replace("\" \":4,", "")),
            valid +
                (
                    spaceIdentifier to
                        """{"providers":[{"type":"space","advances":{" ":4}},{"type":"space","advances":{" ":4}}]}"""
                ),
            valid + (includeDefaultIdentifier to duplicateAscii),
            valid + (includeDefaultIdentifier to include.replace("\"chars\":[$asciiRows]", "\"chars\":[$incompleteRows]")),
        ).forEach(::assertInvalid)
    }

    @Test
    fun rejectsMalformedFiltersAndReferenceCycles() {
        val valid = validDocuments()
        val root = valid.getValue(defaultIdentifier)
        listOf(
            root.replace("{\"uniform\":false}", "false"),
            root.replace("{\"uniform\":false}", "{\"jp\":false}"),
            root.replace("{\"uniform\":false}", "{\"uniform\":false,\"jp\":false}"),
            root.replace("{\"uniform\":false}", "{\"uniform\":\"false\"}"),
        ).forEach { invalidRoot -> assertInvalid(valid + (defaultIdentifier to invalidRoot)) }
        assertInvalid(
            valid +
                (
                    defaultIdentifier to
                        """{"providers":[{"type":"reference","id":"minecraft:default"}]}"""
                ),
        )
    }

    @Test
    fun rejectsMalformedOrUnrepresentableProviderGraphs() {
        val valid = validDocuments()
        val include = includeDefaultIdentifier
        val variants =
            listOf(
                valid + (defaultIdentifier to "{"),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("\"providers\"", "\"providers\":[] , \"ignored\"")),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("\"type\":\"reference\"", "\"type\":1")),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("\"uniform\":false", "\"uniform\":true")),
                valid + (include to valid.getValue(include).replace("\"height\":12", "\"height\":8.5")),
                valid + (include to valid.getValue(include).replace("\"chars\":[\"\\u00c0\"]", "\"chars\":[\"A\"]")),
                valid + (include to valid.getValue(include).replace("\"ascent\":7", "\"ascent\":\"7\"")),
                valid + (include to valid.getValue(include).replace("\"type\":\"bitmap\"", "\"type\":\"ttf\"")),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("minecraft:include/space", "Bad:ID")),
                valid + (include to valid.getValue(include).replace("minecraft:font/ascii.png", "Bad:ID")),
                valid + (include to valid.getValue(include).replace("\"chars\":[$asciiRows]", "\"chars\":[1]")),
            )

        variants.forEach { documents ->
            assertInvalid(documents)
        }
    }

    @Test
    fun wrapsMalformedJsonAndPreservesDocumentReadFailures() {
        val malformed = validDocuments() + (defaultIdentifier to "{")
        val malformedFailure =
            assertThrows(IllegalArgumentException::class.java) {
                validateMinecraftRegularFontContract(asciiImage(), reader(malformed))
            }
        assertTrue(malformedFailure.cause is JsonParseException)

        val readFailure = IOException("read")
        val escaped =
            assertThrows(IOException::class.java) {
                validateMinecraftRegularFontContract(asciiImage()) { throw readFailure }
            }
        assertSame(readFailure, escaped)
    }

    private fun validDocuments(): Map<Identifier, String> =
        mapOf(
            defaultIdentifier to
                """{"providers":[{"type":"reference","id":"minecraft:include/space"},{"type":"reference","id":"minecraft:include/default","filter":{"uniform":false}},{"type":"reference","id":"minecraft:include/unifont"}]}""",
            spaceIdentifier to """{"providers":[{"type":"space","advances":{" ":4,"\u200c":0}}]}""",
            includeDefaultIdentifier to
                """{"providers":[{"type":"bitmap","file":"minecraft:font/accented.png","height":12,"ascent":10,"chars":["\u00c0"]},{"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":[$asciiRows]}]}""",
            unifontIdentifier to
                """{"providers":[{"type":"unihex","hex_file":"minecraft:font/unifont_jp_patch.hex","filter":{"jp":true}},{"type":"unihex","hex_file":"minecraft:font/unifont_all_no_pua.hex"}]}""",
        )

    private fun reader(documents: Map<Identifier, String>): (Identifier) -> String = { identifier -> documents.getValue(identifier) }

    private fun assertInvalid(documents: Map<Identifier, String>) {
        assertThrows(IllegalArgumentException::class.java) {
            validateMinecraftRegularFontContract(asciiImage(), reader(documents))
        }
    }

    private fun asciiImage(): DrawImage = createDrawImage(IntSize(128, 128), IntArray(128 * 128) { 0x00FFFFFF })

    private companion object {
        private val defaultIdentifier: Identifier = Identifier.parse("minecraft:font/default.json")
        private val spaceIdentifier: Identifier = Identifier.parse("minecraft:font/include/space.json")
        private val includeDefaultIdentifier: Identifier = Identifier.parse("minecraft:font/include/default.json")
        private val unifontIdentifier: Identifier = Identifier.parse("minecraft:font/include/unifont.json")
        private val asciiRows: String =
            (0 until 16).joinToString(",") { row ->
                val encoded = (0 until 16).joinToString("") { column -> "\\u%04x".format(row * 16 + column) }
                "\"$encoded\""
            }
    }
}
