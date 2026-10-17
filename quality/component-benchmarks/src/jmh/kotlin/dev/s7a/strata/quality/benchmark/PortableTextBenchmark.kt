package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.ui.UiDefinition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.nio.file.Files
import java.nio.file.Path

/**
 * Separates fresh host/glyph extraction from ordered composition of detached, already generated glyphs.
 * Font source acquisition and decoding are untimed, and the physical output remains full HD at both densities.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PortableTextBenchmark {
    /**
     * Opens, attaches, extracts and closes a fresh owner; this includes cold glyph generation and layout.
     */
    @Benchmark
    public fun extractGlyphs(state: TextPixels): RuntimeUiFrame = state.extract()

    /**
     * Composes the complete detached glyph stream over alternating opaque destinations into new output storage.
     */
    @Benchmark
    public fun composePreparedGlyphs(state: TextPixels): HeadlessImage = state.paint()

    /**
     * Owns immutable profile inputs and detached command streams, with no retained host or output raster.
     */
    @State(Scope.Thread)
    public open class TextPixels {
        /**
         * Ordinary multilingual bitmap labels or the original geometric TrueType fixture at large sizes.
         */
        @JvmField
        @Param
        public var face: Face = Face.Ordinary

        /**
         * Final logical-to-physical density with an unchanged 1920 by 1080 output extent.
         */
        @JvmField
        @Param("1", "4")
        public var density: Int = 1
        private lateinit var profile: MinecraftUiProfile
        private lateinit var viewport: IntSize
        private lateinit var text: String
        private lateinit var frames: List<List<DrawCommand>>
        private var phase = 0

        /**
         * Prepares exact font bytes and glyph commands before sampling, with all temporary owners closed.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(density in setOf(1, 4))
            viewport = IntSize(1920 / density, 1080 / density)
            val snapshot = snapshot()
            check(snapshot.diagnostics.isEmpty())
            profile = ComponentProfile.create(snapshot)
            text = "A日한🙂 ".repeat(128)
            val commands = extract().drawCommands
            check(commands.any { it is DrawCommand.SampledImage || it is DrawCommand.BlitImage })
            val bounds = IntRect(0, 0, viewport.width, viewport.height)
            frames =
                listOf(0xFF234567.toInt(), 0xFF7195B3.toInt()).map { background ->
                    listOf(DrawCommand.FillRectangle(bounds, ArgbColor(background))) + commands
                }
        }

        /**
         * Returns a detached frame after its independent native font/host owner has closed.
         */
        public fun extract(): RuntimeUiFrame =
            createMinecraftUiHost(UiDefinition("Portable Unicode text") { Text(text, TextLayout.Multiline()) }, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                host.attach()
                host.frame(viewport)
            }

        /**
         * Alternates destination pixels without caching or mutating a preceding output.
         */
        public fun paint(): HeadlessImage {
            phase = 1 - phase
            return rasterizeHeadless(frames[phase], viewport, density)
        }

        private fun snapshot(): MinecraftFontSnapshot {
            if (face == Face.Ordinary) return ComponentFontAssets.snapshot()
            val source =
                MinecraftMemoryFontAssetSource(
                    "portable-text-${face.name}-v1",
                    mapOf(
                        "assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:fixture.ttf","size":${face.size},"oversample":1}]}""".toByteArray(Charsets.UTF_8),
                        "assets/strata_benchmark/font/fixture.ttf" to Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture")))),
                    ),
                )
            return MinecraftFontSnapshot.load(listOf(source), FontPerformanceAssets.compatibility(FontWorkload.FreeTypeCached))
        }
    }

    /**
     * Independent font workloads whose sizes are part of the fixed input, not application screen identifiers.
     *
     * @property size logical glyph size, independent of final raster density.
     */
    public enum class Face(
        public val size: Int,
    ) {
        /**
         * Existing multilingual bitmap source with eight-pixel logical glyphs.
         */
        Ordinary(8),

        /**
         * Original TrueType outlines at 64 logical pixels.
         */
        Custom64(64),

        /**
         * Original TrueType outlines at 256 logical pixels.
         */
        Custom256(256),
    }
}
