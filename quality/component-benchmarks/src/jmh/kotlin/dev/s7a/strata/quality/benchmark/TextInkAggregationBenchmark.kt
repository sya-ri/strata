package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
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
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.ui.UiDefinition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete retained Text/TextArea operations over frozen detached glyph inputs.
 * Preparation, initial layout, resource acquisition, diagnostics, pixel comparison and close are untimed.
 * Each timed call includes public value publication/assignment when changed, the retained frame and its returned output.
 * Scalar counts include hard breaks; every sixteenth scalar except the final scalar is LF, with no soft wrapping.
 * This CPU corpus does not measure native font acquisition, uploads, GPU completion or loaded-client parity.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextInkAggregationBenchmark {
    /**
     * Runs one complete selected operation with the shared JMH collector owning time and allocation measurements.
     */
    @Benchmark
    public fun frame(state: TextSession): RuntimeUiFrame = state.frame()

    /**
     * One retained owner with immutable alternating inputs, fixed 320 by 240 viewport and stable frame time.
     */
    @State(Scope.Thread)
    public open class TextSession {
        /**
         * Complete Unicode scalar count, including the corpus's declared LF separators.
         */
        @JvmField
        @Param("1", "16", "256", "4096")
        public var scalars: Int = 1

        /**
         * Detached compatibility or real-engine synthetic provider corpus.
         */
        @JvmField
        @Param
        public var provider: Provider = Provider.Legacy

        /**
         * Public retained operation, including the clean no-saving control.
         */
        @JvmField
        @Param
        public var operation: Operation = Operation.ChangedDisplayNormal

        private lateinit var source: StressStateSource<String>
        private lateinit var editor: TextAreaState
        private lateinit var host: MinecraftUiHost
        private lateinit var inputs: List<String>
        private var phase = 0
        private var backends = 0
        private var faces = 0
        private var monitor: RuntimeWorkMonitor? = null
        private val viewport = IntSize(320, 240)
        private val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x4080C0FF, 0xCC4080C0.toInt(), 0x00FFFFFF))

        /**
         * Freezes both equal-metric complete values and primes the actual retained layout before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(scalars in setOf(1, 16, 256, 4096))
            val first =
                CharArray(scalars) { index ->
                    if (index % 16 == 15 && index < scalars - 1) {
                        '\n'
                    } else if (index % 2 == 0) {
                        'A'
                    } else {
                        'B'
                    }
                }.concatToString()
            inputs = listOf(first, "C" + first.substring(1))
            source = StressStateSource(first)
            editor = TextAreaState(first, maxLength = scalars)
            phase = 0
            val profile = ComponentProfile.create(if (provider == Provider.Legacy) null else snapshot())
            val definition =
                UiDefinition("Text ink aggregation") {
                    Stack {
                        if (operation == Operation.ChangedEditorEnabled) {
                            TextArea(editor, TextAreaViewport.Size(viewport), wrap = TextWrap.None)
                        } else {
                            Text(source, TextLayout.Multiline(TextWrap.None), if (operation == Operation.ChangedDisplayContainerLabel) TextStyle.ContainerLabel else TextStyle.Normal)
                        }
                    }
                }
            host = createMinecraftUiHost(definition, profile, fontBackend = MinecraftFontBackendFactory { backend() })
            host.attach()
            host.frame(viewport, FrameTime(0))
            frame()
            frame()
        }

        /**
         * Includes publication and ordinary frame extraction; clean frames repeat the identical committed value.
         */
        public fun frame(): RuntimeUiFrame {
            if (operation != Operation.CleanDisplayNormal) {
                phase = 1 - phase
                if (operation == Operation.ChangedEditorEnabled) editor.value = inputs[phase] else source.publish(inputs[phase])
            }
            return monitor?.sample { host.frame(viewport, FrameTime(0)) } ?: host.frame(viewport, FrameTime(0))
        }

        /**
         * Enables the shared diagnostic collector outside timed operations.
         */
        public fun monitorWork(): RuntimeWorkMonitor {
            check(monitor == null)
            return RuntimeWorkMonitor(host, checkpointSamples = 16, maxNodeRecords = 64).also {
                monitor = it
            }
        }

        /**
         * Complete committed value corresponding to the latest operation.
         */
        public val currentValue: String get() = inputs[phase]

        /**
         * Independently current face/backend ownership and state subscriptions for untimed admission.
         */
        public val ownedResources: Int get() = backends + faces

        /**
         * Active source callbacks, independent of font lookup and quad counts.
         */
        public val subscriptions: Int get() = source.subscriptions

        private fun snapshot(): MinecraftFontSnapshot =
            MinecraftFontSnapshot
                .load(
                    listOf(
                        MinecraftMemoryFontAssetSource(
                            "text-ink-v1",
                            mapOf(
                                "assets/minecraft/font/default.json" to """{"providers":[{"type":"ttf","file":"strata_benchmark:ink.ttf"}]}""".toByteArray(Charsets.UTF_8),
                                "assets/strata_benchmark/font/ink.ttf" to byteArrayOf(1),
                            ),
                        ),
                    ),
                    MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, preparedTextBounds = true),
                ).also { check(it.diagnostics.isEmpty()) }

        private fun backend(): MinecraftFontBackend {
            backends += 1
            return object : MinecraftFontBackend {
                private var closed = false

                override fun decodePng(bytes: ByteArray): DrawImage = error("The text ink corpus has no bitmap provider")

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
                            val advance = if (provider == Provider.SampledSigned && codePoint == 'B'.code) -3.25f else 3.25f
                            return if (provider == Provider.SpacingOnly) {
                                MinecraftFontGlyph(advance, 0f, 0f, 0f, 0f, null)
                            } else {
                                MinecraftFontGlyph(advance, -0.375f, -0.25f, 1.375f, 1.75f, image, shadowOffset = 1.5f)
                            }
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
         * Releases the sole owner and requires zero live callbacks and font resources.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                host.close()
            }
            check(ownedResources == 0 && subscriptions == 0)
        }
    }

    /**
     * Fixed provider semantics, distinct from the old PortableTextBenchmark's unchanged native workloads.
     */
    public enum class Provider {
        /**
         * Validated printable ASCII snapshots with exact integer positions.
         */
        Legacy,

        /**
         * Fractional bearings with positive scalar advances through the real font engine.
         */
        SampledForward,

        /**
         * Exactly cancelling positive and negative advances with overlapping quads through the real engine.
         */
        SampledSigned,

        /**
         * Logical advances with no image or submitted quad.
         */
        SpacingOnly,
    }

    /**
     * Whole-operation invalidation boundaries; setup always excludes the initial frame.
     */
    public enum class Operation {
        ChangedDisplayNormal,
        ChangedDisplayContainerLabel,
        CleanDisplayNormal,
        ChangedEditorEnabled,
    }

    /**
     * Generalized fixture discovery invokes this verifier before timing, with no fixture-specific build task.
     */
    public companion object {
        /**
         * Admits all 64 new cases and all 152 rows, including the old 12 PortableTextBenchmark cases.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(TextInkAggregationBenchmark::class.java), setOf("avgt", "sample")).size == 128)
            check(JmhWorkloadInventory.capture(listOf(TextInkAggregationBenchmark::class.java, PortableTextBenchmark::class.java), setOf("avgt", "sample")).size == 152)
            TextInkAggregationWorkEvidence.verify()
        }
    }
}
