package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies context-free conversion from every common text variant into the supported component model.
 */
internal class FabricMinecraftTextMappingTest {
    @Test
    fun mapsLiteralConcatenatedAndTranslatedFallbackValues() {
        assertEquals("literal", mapMinecraftText(UiText.Literal("literal")).getString())

        val concatenated = mapMinecraftText(UiText.Concatenated(UiText.Literal("first"), UiText.Literal("second")))
        assertEquals("firstsecond", concatenated.getString())
        assertEquals(listOf("first", "second"), concatenated.getSiblings().map(Component::getString))

        val useKey = mapMinecraftText(UiText.Translated("strata.key"))
        val useKeyContents = useKey.getContents() as TranslatableContents
        assertEquals("strata.key", useKeyContents.getKey())
        assertNull(useKeyContents.getFallback())

        val literalFallback =
            mapMinecraftText(
                UiText.Translated(
                    "strata.fallback",
                    fallback = TranslationFallback.Literal("fallback"),
                ),
            ).getContents() as TranslatableContents
        assertEquals("fallback", literalFallback.getFallback())
    }

    @Test
    fun mapsEveryTypedTranslationArgumentWithoutStringifyingIt() {
        val mapped =
            mapMinecraftText(
                UiText.Translated(
                    key = "strata.arguments",
                    arguments =
                        listOf(
                            UiTextArgument.Text(UiText.Literal("nested")),
                            UiTextArgument.StringValue("string"),
                            UiTextArgument.IntValue(3),
                            UiTextArgument.LongValue(4L),
                            UiTextArgument.FloatValue(5.5f),
                            UiTextArgument.DoubleValue(6.5),
                            UiTextArgument.BooleanValue(true),
                        ),
                ),
            ).getContents() as TranslatableContents

        val arguments = mapped.getArgs()
        assertEquals("nested", (arguments[0] as Component).getString())
        assertEquals("string", arguments[1])
        assertEquals(3, arguments[2])
        assertEquals(4L, arguments[3])
        assertEquals(5.5f, arguments[4])
        assertEquals(6.5, arguments[5])
        assertEquals(true, arguments[6])
    }

    @Test
    fun rejectsOpaquePlatformText() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                mapMinecraftText(UiText.Platform(TestPlatformText("value")))
            }
        assertTrue(failure.message.orEmpty().contains("Platform text"))
    }

    private data class TestPlatformText(
        val value: String,
    ) : PlatformText
}
