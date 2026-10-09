package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Test-thread synthetic fonts with independently selected advances and counted uncached glyph calls.
 * Asset marker bytes are decoded into a typed family at the backend boundary.
 * Runs retain only detached white pixels; neither shaping callbacks nor these fixture counters belong to runtime content.
 */
internal class MinecraftTextProvenanceFontFixture(
    private val advances: Map<Int, Float> = emptyMap(),
    saturatingCeil: Boolean = false,
) : AutoCloseable {
    /**
     * Resource identifiers and independently expected per-family advances.
     */
    internal enum class Family(
        internal val id: ResourceId,
        internal val advance: Float,
    ) {
        First(ResourceId("test", "first"), 3f),
        Second(ResourceId("test", "second"), 5f),
    }

    /**
     * Ordered actual face calls with the original selected family, copied by callers before replacement.
     */
    internal val calls = ArrayList<Pair<Family, Int>>()

    /**
     * Optional complete-line visual source indices; null uses the independent logical scalar order.
     */
    internal var visual: List<MinecraftVisualGlyph>? = null

    /**
     * Optional exact glyph failure injected after construction-time Unicode validation.
     */
    internal var failedScalar: Int? = null

    /**
     * Owned live faces and terminal close calls, including layout failure cleanup.
     */
    internal var liveFaces: Int = 0
        private set

    /**
     * Number of owned backend close attempts.
     */
    internal var backendCloses: Int = 0
        private set

    /**
     * Immutable one-pixel source used by independent draw-command and physical-pixel references.
     */
    internal val image: DrawImage = createDrawImage(IntSize(1, 1), intArrayOf(-1))

    private val snapshot =
        FontTestResources.snapshot(
            FontTestResources.font("minecraft:default", """{"type":"reference","id":"test:first"}"""),
            FontTestResources.font("test:first", """{"type":"ttf","file":"test:first.ttf","size":3}"""),
            FontTestResources.font("test:second", """{"type":"ttf","file":"test:second.ttf","size":5}"""),
            "assets/test/font/first.ttf" to byteArrayOf(Family.First.ordinal.toByte()),
            "assets/test/font/second.ttf" to byteArrayOf(Family.Second.ordinal.toByte()),
            capabilities = FontTestResources.compatibility.copy(saturatingCeil = saturatingCeil),
        )

    private val backend =
        object : MinecraftFontBackend by FontTestBackend() {
            override fun visualGlyphs(
                text: String,
                rightToLeft: Boolean,
            ): List<MinecraftVisualGlyph> =
                visual ?: buildList {
                    var offset = 0
                    while (offset < text.length) {
                        add(MinecraftVisualGlyph(text.codePointAt(offset), offset))
                        offset += Character.charCount(text.codePointAt(offset))
                    }
                }

            override fun openTrueType(
                bytes: ByteArray,
                settings: MinecraftTrueTypeSettings,
            ): MinecraftTrueTypeFace {
                val family = Family.entries[bytes.single().toInt()]
                liveFaces++
                return object : MinecraftTrueTypeFace {
                    private var closed = false

                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        calls.add(family to codePoint)
                        if (failedScalar == codePoint) error("Injected provenance glyph failure.")
                        val advance = advances[codePoint] ?: family.advance
                        return MinecraftFontGlyph(advance, 0f, 0f, 1f, 1f, image)
                    }

                    override fun close() {
                        if (closed) return
                        closed = true
                        liveFaces--
                    }
                }
            }

            override fun close() {
                backendCloses++
            }
        }

    /**
     * Profile and borrowed renderer share immutable resource inputs but no presentation or mutable provenance.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    internal val profile: MinecraftUiProfile = MinecraftProfileFixture.create(fontSnapshot = snapshot)

    /**
     * Uncached source-backed renderer exposing exact logical and shaped font call order.
     */
    internal val renderer: MinecraftTextRenderer = MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }, cacheEntries = 0))

    override fun close() {
        renderer.close()
    }
}
