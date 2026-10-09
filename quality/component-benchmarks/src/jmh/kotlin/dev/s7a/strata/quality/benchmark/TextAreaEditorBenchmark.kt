package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
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
 * Real public TextArea operations with frozen synthetic CPU fonts, completed retained frames and separate editing controls.
 * No native font library, loaded game, raster upload or GPU consumer runs inside these operations.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextAreaEditorBenchmark {
    /**
     * Extracts a clean retained frame at an unchanged explicit timestamp.
     */
    @Benchmark
    public fun cleanFrame(session: Session): RuntimeUiFrame = session.frame()

    /**
     * Assigns an independently copied equal canonical value and completes its retained frame.
     */
    @Benchmark
    public fun equalFrame(session: Session): RuntimeUiFrame = session.equalFrame()

    /**
     * Alternates unequal public values and completes all required content, layout, paint and semantics work.
     */
    @Benchmark
    public fun assignmentFrame(session: Session): RuntimeUiFrame = session.assignmentFrame()

    /**
     * Delivers real insert and delete input through the focused host, including required geometry synchronization.
     * No explicit frame is extracted; required edit-string construction remains inside the operation.
     */
    @Benchmark
    public fun editCycle(session: Session): String = session.editCycle()

    /**
     * Delivers one scalar insertion and deletion and completes a retained frame after each input: two frames per operation.
     */
    @Benchmark
    public fun editFrames(session: Session): RuntimeUiFrame = session.editFrames()

    /**
     * Constructs, attaches, extracts and closes one independent complete editor lifetime.
     */
    @Benchmark
    public fun lifecycle(session: Session): RuntimeUiFrame = session.lifecycle()

    /**
     * Current editor and bounded immutable assets; monitor counters are enabled only by the untimed verifier.
     */
    @State(Scope.Thread)
    public open class Session {
        /**
         * Frozen starting UTF-16 size; two extra units allow the actual supplementary insertion.
         */
        @JvmField
        @Param("8", "2048", "16384")
        public var length: Int = 8

        /**
         * Hard-break and wrapped editor controls preserve independent real layout work.
         */
        @JvmField
        @Param("None", "Character")
        public var wrap: TextWrap = TextWrap.None

        /**
         * Whether root and deferred-region content both depend on the current public text value.
         */
        @JvmField
        @Param("false", "true")
        public var observed: Boolean = false

        /**
         * Current authoritative text, independent of host ownership.
         */
        public lateinit var area: TextAreaState

        /**
         * Prepared canonical inputs and equal reference.
         */
        public lateinit var texts: List<String>

        private lateinit var equal: String
        private lateinit var profile: MinecraftUiProfile
        private lateinit var fonts: TextAreaBenchmarkFonts
        private lateinit var host: MinecraftUiHost
        private val source = StressStateSource(0)
        private val viewport = IntSize(320, 160)
        private val insert = TextInputEvent.Character(0x1F642)
        private val delete = KeyboardEvent.Press(KeyCode.Backspace, 0)
        private var revision = 0
        private var monitor: RuntimeWorkMonitor? = null
        private var rootEvaluations = 0
        private var regionEvaluations = 0
        private var sourceSeen = 0

        /**
         * Builds immutable font assets and commits one initial focused frame outside collection.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val first = "A" + "日🙂\ne\u0301\u2066B\u2069".repeat(length / 9).take(length - 1)
            val base = if (first.last() in '\uD800'..'\uDBFF') first.dropLast(1) + "A" else first
            texts = listOf(base.padEnd(length, 'A'), "B" + base.padEnd(length, 'A').substring(1))
            equal = texts[0].toCharArray().concatToString()
            fonts = TextAreaBenchmarkFonts()
            profile = fonts.profile()
            area = TextAreaState(texts[0], maxLength = length + 2)
            host = fresh(area)
            host.attach()
            frame()
        }

        /**
         * Complete current frame with monitoring disabled in timed collection.
         */
        public fun frame(): RuntimeUiFrame = measured { host.frame(viewport, FrameTime(0)) }

        /**
         * Equal-value frame control through the full public setter.
         */
        public fun equalFrame(): RuntimeUiFrame =
            measured {
                area.value = equal
                host.frame(viewport, FrameTime(0))
            }

        /**
         * One changed public assignment followed by a completed retained frame.
         */
        public fun assignmentFrame(): RuntimeUiFrame =
            measured {
                revision = 1 - revision
                area.value = texts[revision]
                host.frame(viewport, FrameTime(0))
            }

        /**
         * Real input cycle without explicit frame extraction; validation is never bypassed.
         */
        public fun editCycle(): String {
            host.dispatchTextInput(insert)
            host.dispatchKeyboard(delete)
            return area.value
        }

        /**
         * Two changed input frames with an actual scalar insertion/deletion between them.
         */
        public fun editFrames(): RuntimeUiFrame =
            measured {
                host.dispatchTextInput(insert)
                host.frame(viewport, FrameTime(0))
                host.dispatchKeyboard(delete)
                host.frame(viewport, FrameTime(0))
            }

        /**
         * Independent cold ownership and terminal cleanup, with prepared immutable assets shared read-only.
         */
        public fun lifecycle(): RuntimeUiFrame =
            fresh(TextAreaState(texts[0], length + 2)).use { next ->
                next.attach()
                next.frame(viewport, FrameTime(0))
            }

        private inline fun measured(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()

        private fun fresh(state: TextAreaState): MinecraftUiHost =
            createMinecraftUiHost(
                UiDefinition("Canonical multiline editor") {
                    if (monitor != null) rootEvaluations += 1
                    if (observed) state.value
                    Stack {
                        Observe(source) { value ->
                            if (monitor != null) {
                                regionEvaluations += 1
                                sourceSeen = value
                            }
                            if (observed) state.value
                            TextArea(state, TextAreaViewport.Size(IntSize(300, 140)), wrap = wrap, modifier = Modifier.Empty.initialFocus())
                        }
                    }
                },
                profile,
                fontBackend = MinecraftFontBackendFactory { fonts.backend() },
            )

        /**
         * Enables shared untimed work collection, checks real input, source batching and retained lifecycle cleanup.
         */
        public fun verify() {
            val original = frame()
            val pixels = rasterizeHeadless(original.drawCommands, viewport).copyArgb().toList()
            val scroll = area.scrollState
            val focus = checkNotNull(host.textInputFocus)
            check(equalFrame() === original)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 16, maxNodeRecords = 64)
            assignmentFrame()
            check(rootEvaluations == if (observed) 1 else 0)
            check(regionEvaluations == if (observed) 1 else 0)
            check(area.value == texts[1])
            check(host.textInputFocus == focus)
            check(editCycle() == texts[1])
            check(editFrames().semantics.isNotEmpty())
            check(area.value == texts[1])
            assignmentFrame()
            check(area.value == texts[0])
            check(rasterizeHeadless(original.drawCommands, viewport).copyArgb().toList() == pixels)
            check(area.scrollState === scroll)
            source.publish(1)
            source.publish(2)
            val beforeRegion = regionEvaluations
            frame()
            check(sourceSeen == 2)
            check(regionEvaluations == beforeRegion + 1)
            check(checkNotNull(monitor).snapshot().entrySet().isNotEmpty())
            val held = fonts.resources
            lifecycle()
            check(fonts.resources == held)
            host.detach()
            host.attach()
            frame()
            check(area.value == texts[0])
            check(area.scrollState === scroll)
        }

        /**
         * Releases diagnostics and the complete host; caller-owned state remains usable.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                host.close()
            }
            check(fonts.resources == 0)
            check(source.subscriptions == 0)
            area.observe {}.close()
            area.value = "closed"
        }
    }

    /**
     * Work admission remains discoverable through the existing generic fixture selector.
     */
    public companion object {
        /**
         * Verifies all 12 input/layout/dependency combinations outside measured intervals.
         */
        @JvmStatic
        public fun verifyWork() {
            for (length in listOf(8, 2_048, 16_384)) {
                for (wrap in listOf(TextWrap.None, TextWrap.Character)) {
                    for (observed in listOf(false, true)) {
                        val session =
                            Session().also {
                                it.length = length
                                it.wrap = wrap
                                it.observed = observed
                                it.setup()
                            }
                        try {
                            session.verify()
                        } finally {
                            session.close()
                        }
                    }
                }
            }
        }
    }
}
