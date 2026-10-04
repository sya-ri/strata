package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Supplemental public TextField operations using fixed exceptional metrics and the actual resource-font engine.
 * Synthetic faces supply detached empty glyphs; this measures width/control work, not native font rasterization.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ExceptionalTextFieldBenchmark {
    /**
     * Reuses a clean retained frame without changing input or time.
     */
    @Benchmark
    public fun idle(state: TextSession): RuntimeUiFrame = state.idle()

    /**
     * Changes the public editor value and extracts its resulting retained frame.
     */
    @Benchmark
    public fun update(state: TextSession): RuntimeUiFrame = state.update()

    /**
     * Creates, attaches, extracts and closes an independent complete editor lifetime.
     */
    @Benchmark
    public fun lifecycle(state: TextSession): RuntimeUiFrame = state.lifecycle()

    /**
     * Worker-owned immutable inputs and current host; preparation and diagnostics stay outside timed operations.
     */
    @State(Scope.Thread)
    public open class TextSession {
        /**
         * Fixed signed scalar metrics registered by the supplemental corpus.
         */
        @JvmField
        @Param
        public var workload: ExceptionalTextWorkload = ExceptionalTextWorkload.SignedFractional

        /**
         * Both actual native rounding contracts, with no inferred game version.
         */
        @JvmField
        @Param("false", "true")
        public var saturating: Boolean = false

        private lateinit var profile: MinecraftUiProfile
        private lateinit var host: MinecraftUiHost
        private lateinit var field: TextFieldState
        private lateinit var texts: List<String>
        private lateinit var metrics: Map<Int, Float>
        private var monitor: RuntimeWorkMonitor? = null
        private var revision = 0
        private var backends = 0
        private var faces = 0
        private val viewport = IntSize(320, 40)

        /**
         * Prepares resources and both public editor values before collecting an interval.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val advances =
                when (workload) {
                    ExceptionalTextWorkload.SignedFractional -> listOf(1.25f, -0.1f, 0f, 0f)
                    ExceptionalTextWorkload.FiniteCancellation -> listOf(1e35f, -1e35f, 1e29f, 1e38f)
                    ExceptionalTextWorkload.InfiniteTail -> listOf(0.1f, -0.25f, Float.POSITIVE_INFINITY, 0f)
                }
            metrics = listOf('A', 'B', 'C', 'D').mapIndexed { index, value -> value.code to advances[index] }.toMap() + ('E'.code to advances.first())
            val initial =
                when (workload) {
                    ExceptionalTextWorkload.SignedFractional -> "AB".repeat(8_192)
                    ExceptionalTextWorkload.FiniteCancellation -> "ABC".repeat(8_192) + "D"
                    ExceptionalTextWorkload.InfiniteTail -> "AB".repeat(8_192) + "C"
                }
            texts = listOf(initial, "E" + initial.substring(1))
            val source =
                MinecraftMemoryFontAssetSource(
                    "exceptional-editor-v1",
                    mapOf(
                        "assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:input.ttf"}]}""".toByteArray(Charsets.UTF_8),
                        "assets/strata_benchmark/font/input.ttf" to byteArrayOf(1),
                    ),
                )
            val snapshot = MinecraftFontSnapshot.load(listOf(source), MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, saturatingCeil = saturating))
            check(snapshot.diagnostics.isEmpty())
            profile = ComponentProfile.create(snapshot)
            field = TextFieldState(initial, maxLength = initial.length)
            host = fresh(field)
            host.attach()
            host.frame(viewport, FrameTime(0))
            update()
            update()
        }

        /**
         * Starts bounded shared work diagnostics only for untimed admission.
         */
        public fun monitorWork() {
            check(monitor == null)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 16, maxNodeRecords = 64)
        }

        /**
         * Extracts an unchanged current frame.
         */
        public fun idle(): RuntimeUiFrame = measured { host.frame(viewport, FrameTime(0)) }

        /**
         * Alternates equal-metric values through the public state invalidation path.
         */
        public fun update(): RuntimeUiFrame =
            measured {
                revision += 1
                field.value = texts[revision % texts.size]
                host.frame(viewport, FrameTime(0))
            }

        /**
         * Releases an independent lifetime before returning its detached frame.
         */
        public fun lifecycle(): RuntimeUiFrame =
            fresh(TextFieldState(field.value, field.maxLength)).use { next ->
                next.attach()
                next.frame(viewport, FrameTime(0))
            }

        /**
         * Detached diagnostic counts, never collected by the timed fixture unless explicitly enabled.
         */
        public val diagnostics: JsonObject get() = checkNotNull(monitor).snapshot()

        /**
         * Currently owned backend and face resources, excluding immutable prepared inputs.
         */
        public val ownedResources: Int get() = backends + faces

        /**
         * Current observable editor value for admission of real update and semantics work.
         */
        public val value: String get() = this.field.value

        private inline fun measured(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()

        private fun fresh(editor: TextFieldState): MinecraftUiHost =
            createMinecraftUiHost(
                UiDefinition("Exceptional literal editor") { Stack { TextField(editor, IntSize(300, 20)) } },
                profile,
                fontBackend = MinecraftFontBackendFactory { openBackend() },
            )

        private fun openBackend(): MinecraftFontBackend {
            backends += 1
            return object : MinecraftFontBackend {
                private var closed = false

                override fun decodePng(bytes: ByteArray): DrawImage = error("The exceptional metric fixture has no bitmap providers")

                override fun openTrueType(
                    bytes: ByteArray,
                    settings: MinecraftTrueTypeSettings,
                ): MinecraftTrueTypeFace {
                    check(closed.not())
                    faces += 1
                    return object : MinecraftTrueTypeFace {
                        private var closed = false

                        override fun glyph(codePoint: Int): MinecraftFontGlyph {
                            check(closed.not())
                            return MinecraftFontGlyph(metrics[codePoint] ?: 1f, 0f, 0f, 0f, 0f, null)
                        }

                        override fun close() {
                            if (closed) return
                            closed = true
                            faces -= 1
                        }
                    }
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    backends -= 1
                }
            }
        }

        /**
         * Closes diagnostics and the current host, requiring complete font-resource release.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                host.close()
            }
            check(ownedResources == 0)
        }
    }
}
