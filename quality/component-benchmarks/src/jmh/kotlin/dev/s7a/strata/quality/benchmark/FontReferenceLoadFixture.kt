package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings

/**
 * Prepared immutable document bytes reused only as inputs to fresh public snapshot loads.
 * Encoding and source construction stay outside timing; reads, parsing, graph resolution and final snapshots remain inside.
 */
internal class FontReferenceLoadFixture(
    private val case: FontReferenceLoadCase,
) {
    private val compatibility = MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84)
    private val options = MinecraftFontOptions(uniform = case == FontReferenceLoadCase.FilteredLeafAlias)
    private val source: MinecraftMemoryFontAssetSource
    private val expectedFonts: Set<ResourceId>
    private val root = ResourceId("reference_load", "root")

    init {
        val files = linkedMapOf<String, ByteArray>()
        val fonts = linkedSetOf<ResourceId>()
        fun document(name: String, providers: String) {
            files["assets/reference_load/font/$name.json"] = """{"providers":[$providers]}""".toByteArray(Charsets.UTF_8)
            fonts += ResourceId("reference_load", name)
        }
        val innerFilter = if (case in setOf(FontReferenceLoadCase.FilteredLeafAlias, FontReferenceLoadCase.FilteredAlias, FontReferenceLoadCase.MixedChain)) ",\"filter\":{\"uniform\":true}" else ""
        val providers =
            (0 until 64).joinToString(",") { index ->
                val scalar = (65 + index).toString(16).padStart(4, '0')
                """{"type":"space","advances":{"\u$scalar":${3 + index}}$innerFilter}"""
            }
        when (case) {
            FontReferenceLoadCase.Direct -> document("root", providers)
            FontReferenceLoadCase.EmptyTarget -> {
                document("leaf", "")
                document("root", reference("leaf"))
            }

            FontReferenceLoadCase.Alias, FontReferenceLoadCase.FilteredLeafAlias, FontReferenceLoadCase.FilteredAlias -> {
                document("leaf", providers)
                document("root", reference("leaf", case == FontReferenceLoadCase.FilteredAlias))
            }

            FontReferenceLoadCase.Chain, FontReferenceLoadCase.MixedChain -> {
                document("leaf", providers)
                var target = "leaf"
                repeat(7) { index ->
                    val name = if (index == 6) "root" else "chain_$index"
                    document(name, reference(target, case == FontReferenceLoadCase.MixedChain && index % 2 == 0))
                    target = name
                }
            }

            FontReferenceLoadCase.Diamond -> {
                document("leaf", providers)
                document("left", reference("leaf"))
                document("right", reference("leaf"))
                document("root", "${reference("left")},${reference("right")}")
            }
        }
        source = MinecraftMemoryFontAssetSource("reference-load", files)
        expectedFonts = fonts.toSet()
    }

    /** Loads a new detached snapshot through the public entry point without opening an engine. */
    fun load(): MinecraftFontSnapshot = MinecraftFontSnapshot.load(listOf(source), compatibility, options)

    /** Checks all loaded families and every space advance outside sampling without native services. */
    fun verify(snapshot: MinecraftFontSnapshot) {
        check(snapshot.fontIds == expectedFonts && snapshot.diagnostics.isEmpty())
        val factory =
            MinecraftFontBackendFactory {
                object : MinecraftFontBackend {
                    override fun decodePng(bytes: ByteArray): DrawImage = error("Space fixtures must not decode images.")

                    override fun openTrueType(
                        bytes: ByteArray,
                        settings: MinecraftTrueTypeSettings,
                    ): MinecraftTrueTypeFace = error("Space fixtures must not open faces.")

                    override fun close() = Unit
                }
            }
        MinecraftFontEngine(snapshot, factory).use { engine ->
            repeat(64) { index ->
                val expected = if (case == FontReferenceLoadCase.EmptyTarget) 6f else (3 + index).toFloat()
                check(engine.glyph(root, 65 + index).advance == expected)
            }
            check(engine.diagnostics.isEmpty())
        }
    }

    private fun reference(
        target: String,
        filtered: Boolean = false,
    ): String {
        val filter = if (filtered) ",\"filter\":{\"uniform\":false}" else ""
        return """{"type":"reference","id":"reference_load:$target"$filter}"""
    }
}
