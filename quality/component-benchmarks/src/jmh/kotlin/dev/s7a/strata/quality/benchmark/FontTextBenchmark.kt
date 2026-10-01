package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual ICU visual ordering with mixed-direction and supplementary input prepared outside measurement.
 */
public open class FontTextBenchmark {
    /**
     * Orders and shapes one complete logical line, preserving real original UTF-16 scalar offsets.
     */
    @Benchmark
    public fun visualGlyphs(state: TextSession): List<MinecraftVisualGlyph> = state.visualGlyphs()

    /**
     * One owner-thread native backend and fixed input length.
     */
    @State(Scope.Thread)
    public open class TextSession {
        /**
         * Declared normal and stress lengths in UTF-16 code units.
         */
        @Param("32", "16384")
        public var length: Int = 32
        private lateinit var text: String
        private lateinit var engine: MinecraftFontEngine

        /**
         * Prepares mixed Latin, Hebrew, Arabic, CJK and supplementary text, then warms the actual backend.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(length in setOf(32, 16384))
            val phrase = "abc אבג العربية 日本語🙂 "
            text = phrase.repeat((length + phrase.length - 1) / phrase.length).take(length)
            if (text.last().isHighSurrogate()) text = text.dropLast(1) + "a"
            check(text.length == length)
            engine = MinecraftFontEngine(ComponentFontAssets.snapshot(), LwjglMinecraftFontBackendFactory)
            visualGlyphs()
        }

        /**
         * Returns the complete detached real shaping result.
         */
        public fun visualGlyphs(): List<MinecraftVisualGlyph> = engine.visualGlyphs(text, rightToLeft = true)

        /**
         * Releases the backend on its worker owner.
         */
        @TearDown(Level.Trial)
        public fun close() {
            engine.close()
        }
    }
}
