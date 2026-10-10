package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
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
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * The complete 120-row geometry corpus: real natural bounds, uncached public glyphs and retained dirty Text.
 * Acquisition, original ZIP creation, decoding and scalar reference checks never occur in sampled setup.
 * Natural bounds include one identical method-handle invocation on both runtime archives.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class UnihexBoundsBenchmark {
    /**
     * Invokes the actual loaded runtime helper once on one immutable owned glyph.
     */
    @Benchmark
    public fun naturalBounds(state: BoundsState): IntRange = state.bounds()

    /**
     * Includes actual raster construction, width validation and public metric creation with caching disabled.
     */
    @Benchmark
    public fun publicUncachedGlyph(state: GlyphState): MinecraftFontGlyph = state.glyph()

    /**
     * Publishes a changed Text value and returns the complete real retained frame with default font caching.
     */
    @Benchmark
    public fun completeDirtyTextFrame(state: TextState): RuntimeUiFrame = state.update()

    /**
     * Shared deterministic admission is discovered by the existing generic fixture verifier.
     */
    public companion object {
        /**
         * Verifies the complete immutable 132-row registry and independent scalar/public/frame checks.
         */
        @JvmStatic
        public fun verifyWork(): Unit = UnihexBoundsWorkEvidence.verify()
    }

    /**
     * Worker-owned immutable row fixture and actual runtime method binding, with no copied implementation.
     */
    @State(Scope.Thread)
    public open class BoundsState {
        /**
         * All six row patterns at each of the four admitted decoded widths.
         */
        @JvmField
        @Param
        public var fixture: UnihexBoundsFixture = UnihexBoundsFixture.W8Empty
        private lateinit var glyph: Any
        private lateinit var method: MethodHandle

        /**
         * Resolves the real archive class and copies its original sixteen packed rows before measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val type = Class.forName("dev.s7a.strata.runtime.minecraft.font.FontHexGlyph")
            val lookup = MethodHandles.publicLookup()
            glyph = lookup.findConstructor(type, MethodType.methodType(Void.TYPE, Int::class.java, LongArray::class.java)).invoke(fixture.width, UnihexBoundsAssets.rows(fixture))
            method = lookup.findVirtual(type, "bounds", MethodType.methodType(IntRange::class.java))
        }

        /**
         * The same handle call reaches baseline or candidate according to the actual execution classpath.
         */
        public fun bounds(): IntRange = method.invoke(glyph) as IntRange
    }

    /**
     * Actual public no-cache glyph preparation after provider preflight; no source generation is sampled.
     */
    @State(Scope.Thread)
    public open class GlyphState {
        /**
         * Exactly the 24 frozen geometry inputs.
         */
        @JvmField
        @Param
        public var fixture: UnihexBoundsFixture = UnihexBoundsFixture.W8Empty

        /**
         * Both release-specific advance capabilities, crossed with identical immutable source bytes.
         */
        @JvmField
        @Param("false", "true")
        public var fractional: Boolean = false
        private lateinit var engine: MinecraftFontEngine
        private var backends = 0

        /**
         * Loads through the public snapshot API and preflights one independently owned engine before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            engine = MinecraftFontEngine(UnihexBoundsAssets.snapshot(fixture, fractional), backendFactory(), cacheEntries = 0, cacheBytes = 0)
            glyph()
        }

        /**
         * Performs the full public uncached consumer operation without extracting pixels inside timing.
         */
        public fun glyph(): MinecraftFontGlyph = engine.glyph(UnihexBoundsAssets.font, 'A'.code)

        /**
         * Closes the actual owner and proves no raster, face or backend survives terminal cleanup.
         */
        @TearDown(Level.Trial)
        public fun close() {
            engine.close()
            check(engine.retainedRasterEntries == 0 && engine.retainedRasterBytes == 0L && engine.retainedFaces == 0 && backends == 0)
        }

        private fun backendFactory(): MinecraftFontBackendFactory = portableBackend { backends += it }
    }

    /**
     * Current Text and one host with the unchanged default raster cache.
     * Both scalar rasters are primed; dirty content still executes normal layout/paint while natural work may be zero.
     */
    @State(Scope.Thread)
    public open class TextState {
        /**
         * Exactly the 24 original source geometries, including transparent empty padding.
         */
        @JvmField
        @Param
        public var fixture: UnihexBoundsFixture = UnihexBoundsFixture.W8Empty

        /**
         * Both actual integer and fractional advance contracts.
         */
        @JvmField
        @Param("false", "true")
        public var fractional: Boolean = false
        private lateinit var host: MinecraftUiHost
        private lateinit var source: StressStateSource<String>
        private var monitor: RuntimeWorkMonitor? = null
        private var phase = false
        private var backends = 0

        /**
         * Fixed complete logical viewport; physical density is independently checked outside CPU timing.
         */
        public val viewport: IntSize = IntSize(320, 40)

        /**
         * Detached untimed diagnostics; no per-frame diagnostic work enters ordinary JMH sampling.
         */
        public val work: JsonObject get() = checkNotNull(monitor).snapshot()

        /**
         * Live source subscription count and independent backend ownership for retention checks.
         */
        public val ownership: List<Int> get() = listOf(source.subscriptions, backends)

        /**
         * Prepares immutable profile inputs, attaches and primes both declared scalars with actual Text frames.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val profile = ComponentProfile.create(UnihexBoundsAssets.snapshot(fixture, fractional))
            source = StressStateSource("A")
            host = createMinecraftUiHost(UiDefinition("Unihex bounds Text") { Text(source, style = TextStyle.ContainerLabel) }, profile, fontBackend = portableBackend { backends += it })
            host.attach()
            idle()
            update()
            update()
        }

        /**
         * Uses the shared bounded monitor only during untimed structural admission.
         */
        public fun monitorWork() {
            check(monitor == null)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 16, maxNodeRecords = 32)
        }

        /**
         * Returns the current clean frame without changing source or host time.
         */
        public fun idle(): RuntimeUiFrame = measured { host.frame(viewport) }

        /**
         * Alternates equal-metric A/B through public observation and performs a complete dirty frame.
         */
        public fun update(): RuntimeUiFrame =
            measured {
                phase = phase.not()
                source.publish(if (phase) "B" else "A")
                host.frame(viewport)
            }

        /**
         * Exercises real detachment and reattachment without replacing immutable source data.
         */
        public fun reattach(): RuntimeUiFrame {
            host.detach()
            host.attach()
            return host.frame(viewport)
        }

        /**
         * An ordinary display label has no eligible input behavior after its actual geometry is committed.
         */
        public fun pointer(): InputResult = host.dispatchPointer(PointerEvent.Move(IntOffset(0, 0)))

        /**
         * Releases monitoring, subscription, host and backend even if an independent assertion fails.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                host.close()
            }
            check(ownership == listOf(0, 0))
        }

        private inline fun measured(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()
    }
}

/**
 * Supplies only CPU-neutral ordering for Unihex fixtures and explicit terminal ownership; no native font is opened.
 * Called outside sampled operations except for the explicitly measured lifecycle controls.
 */
internal fun portableBackend(ownership: (Int) -> Unit): MinecraftFontBackendFactory =
    MinecraftFontBackendFactory {
        ownership(1)
        object : MinecraftFontBackend {
            override fun decodePng(bytes: ByteArray): DrawImage = error("Unihex fixtures do not decode PNGs")

            override fun openTrueType(
                bytes: ByteArray,
                settings: MinecraftTrueTypeSettings,
            ): MinecraftTrueTypeFace = error("Unihex fixtures do not open native faces")

            override fun close() {
                ownership(-1)
            }
        }
    }
