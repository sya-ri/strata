package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Frozen synthetic CPU glyphs with explicit current backend/face ownership; no native decoding or clocks.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class TextAreaBenchmarkFonts {
    private val images = listOf(-1, 0xFFFF8080.toInt()).map { color -> createDrawImage(IntSize(2, 7), IntArray(14) { color }) }

    /**
     * Current resource count, checked outside timing at every independent terminal lifetime.
     */
    var resources = 0
        private set

    /**
     * Prepares the same immutable resource-font graph and texture assets before measured operations.
     */
    fun profile(): MinecraftUiProfile {
        val assets = MinecraftMemoryFontAssetSource(
            "textarea-cpu-font-v1",
            mapOf(
                "assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:area.ttf","size":3}]}""".toByteArray(Charsets.UTF_8),
                "assets/strata_benchmark/font/area.ttf" to byteArrayOf(1),
            ),
        )
        val snapshot = MinecraftFontSnapshot.load(listOf(assets), MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84))
        check(snapshot.diagnostics.isEmpty())
        return ComponentProfile.create(snapshot)
    }

    /**
     * Opens one host-owned CPU backend whose faces return detached synthetic glyphs for every accepted scalar.
     */
    fun backend(): MinecraftFontBackend {
        resources += 1
        return object : MinecraftFontBackend {
            private var closed = false

            override fun decodePng(bytes: ByteArray): DrawImage = error("The frozen CPU text fixture has no bitmap providers")

            override fun openTrueType(bytes: ByteArray, settings: MinecraftTrueTypeSettings): MinecraftTrueTypeFace {
                check(closed.not())
                resources += 1
                return object : MinecraftTrueTypeFace {
                    private var closed = false

                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        check(closed.not())
                        return MinecraftFontGlyph(3f, 0f, 0f, 2f, 7f, images[codePoint % 2])
                    }

                    override fun close() {
                        if (closed) return
                        closed = true
                        resources -= 1
                    }
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                resources -= 1
            }
        }
    }
}
