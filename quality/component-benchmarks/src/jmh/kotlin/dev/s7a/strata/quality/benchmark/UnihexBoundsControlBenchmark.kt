package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Files
import java.nio.file.Path

/**
 * Twelve unchanged consumer controls, including real native CPU fonts and complete load/replacement lifetimes.
 * Every control remains in the registry when it has no natural-bound opportunity or measures slower.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class UnihexBoundsControlBenchmark {
    /**
     * Performs the declared complete consumer operation with setup and reset transitions fixed by the table.
     */
    @Benchmark
    public fun control(state: ControlState): Any = state.operation()

    /**
     * Generic discovery admits this corpus together with all geometry rows.
     */
    public companion object {
        /**
         * Independent work checks run before the shared JMH collector starts any fork.
         */
        @JvmStatic
        public fun verifyWork(): Unit = UnihexBoundsWorkEvidence.verify()
    }

    /**
     * One worker owns its prepared sources and current engine or Text host; it retains no result history.
     */
    @State(Scope.Thread)
    public open class ControlState {
        /**
         * Exactly the twelve frozen single-row operations.
         */
        @JvmField
        @Param
        public var control: UnihexBoundsControl = UnihexBoundsControl.Evicted8Integer
        private lateinit var source: MinecraftMemoryFontAssetSource
        private lateinit var alternative: MinecraftMemoryFontAssetSource
        private lateinit var snapshot: MinecraftFontSnapshot
        private var engine: MinecraftFontEngine? = null
        private var text: UnihexBoundsBenchmark.TextState? = null
        private var phase = false
        private var backends = 0

        /**
         * The independently selected source fixture for untimed scalar/pixel reference checks.
         */
        public val fixture: UnihexBoundsFixture
            get() = if (control == UnihexBoundsControl.Evicted8Integer) UnihexBoundsFixture.W8DisjointExtrema else UnihexBoundsFixture.W32DisjointExtrema

        /**
         * The exact integer/fractional capability used by the public control.
         */
        public val fractional: Boolean get() = control != UnihexBoundsControl.Evicted8Integer

        /**
         * Bounded current cache gauges; no historical counter state is added to the runtime.
         */
        public val retained: List<Long>
            get() = engine?.let { listOf(it.retainedRasterEntries.toLong(), it.retainedRasterBytes, it.retainedFaces.toLong()) } ?: listOf(0L, 0L, 0L)

        /**
         * Acquires original bytes outside timing and primes only the transitions declared in the frozen table.
         */
        @Setup(Level.Trial)
        public fun setup() {
            source = source()
            alternative = UnihexBoundsAssets.source(UnihexBoundsFixture.W32CenteredSparse)
            snapshot = UnihexBoundsAssets.load(source, fractional)
            when (control) {
                UnihexBoundsControl.CleanTextFrame -> {
                    text = UnihexBoundsBenchmark.TextState().also {
                        it.fixture = fixture
                        it.fractional = fractional
                        it.setup()
                    }
                }

                UnihexBoundsControl.SnapshotLoad, UnihexBoundsControl.EngineLifecycle, UnihexBoundsControl.SnapshotReplacement -> {}

                else -> {
                    val entries =
                        when (control) {
                            UnihexBoundsControl.Evicted8Integer, UnihexBoundsControl.Evicted32Fractional -> 1
                            UnihexBoundsControl.WarmRasterHit -> 4096
                            else -> 0
                        }
                    engine = createEngine(entries)
                    checkNotNull(engine).glyph(UnihexBoundsAssets.font, 'A'.code)
                }
            }
        }

        /**
         * Returns the real output to JMH without hashing, pixel extraction or timing thresholds.
         */
        public fun operation(): Any =
            when (control) {
                UnihexBoundsControl.Evicted8Integer, UnihexBoundsControl.Evicted32Fractional -> {
                    phase = phase.not()
                    checkNotNull(engine).glyph(UnihexBoundsAssets.font, if (phase) 'B'.code else 'A'.code)
                }

                UnihexBoundsControl.AbsentSparseGlyph -> checkNotNull(engine).glyph(UnihexBoundsAssets.font, 'C'.code)
                UnihexBoundsControl.SnapshotLoad -> UnihexBoundsAssets.load(source, fractional)
                UnihexBoundsControl.EngineLifecycle -> createEngine(0).use { it.glyph(UnihexBoundsAssets.font, 'A'.code) }
                UnihexBoundsControl.CleanTextFrame -> checkNotNull(text).idle()
                UnihexBoundsControl.SnapshotReplacement -> {
                    phase = phase.not()
                    val next = UnihexBoundsAssets.load(if (phase) alternative else source, fractional)
                    val profile = ComponentProfile.create(next)
                    createMinecraftUiHost(UiDefinition("Unihex snapshot replacement") { Text("A", style = TextStyle.ContainerLabel) }, profile, fontBackend = portableBackend { backends += it }).use {
                        it.attach()
                        it.frame(IntSize(320, 40))
                    }
                }

                else -> checkNotNull(engine).glyph(UnihexBoundsAssets.font, 'A'.code)
            }

        /**
         * Releases every current owner and checks terminal cache and backend gauges after ordinary or failed use.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                text?.close()
            } finally {
                text = null
                engine?.close()
            }
            check(retained == listOf(0L, 0L, 0L) && backends == 0)
            engine = null
        }

        private fun createEngine(entries: Int): MinecraftFontEngine {
            val native = control == UnihexBoundsControl.TrueType || control == UnihexBoundsControl.Bitmap
            val backend = if (native) nativeBackend() else portableBackend { backends += it }
            return MinecraftFontEngine(snapshot, backend, cacheEntries = entries)
        }

        private fun nativeBackend(): MinecraftFontBackendFactory =
            MinecraftFontBackendFactory { capabilities ->
                val delegate = LwjglMinecraftFontBackendFactory.open(capabilities)
                backends += 1
                object : MinecraftFontBackend by delegate {
                    override fun close() {
                        delegate.close()
                        backends -= 1
                    }
                }
            }

        private fun source(): MinecraftMemoryFontAssetSource =
            when (control) {
                UnihexBoundsControl.MatchingOverride -> UnihexBoundsAssets.source(fixture, "matching.json")
                UnihexBoundsControl.NoMatchingOverride -> UnihexBoundsAssets.source(fixture, "nonmatching.json")
                UnihexBoundsControl.Bitmap ->
                    MinecraftMemoryFontAssetSource(
                        "unihex-bitmap-control-v1",
                        mapOf(
                            "assets/minecraft/font/default.json" to UnihexBoundsAssets.bundle("bitmap.json"),
                            "assets/strata_benchmark/textures/font/control.png" to UnihexBoundsAssets.bundle("control.png"),
                        ),
                    )

                UnihexBoundsControl.TrueType ->
                    MinecraftMemoryFontAssetSource(
                        "unihex-truetype-control-v1",
                        mapOf(
                            "assets/minecraft/font/default.json" to UnihexBoundsAssets.bundle("truetype.json"),
                            "assets/strata_benchmark/font/control.ttf" to Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture")))),
                        ),
                    )

                else -> UnihexBoundsAssets.source(fixture)
            }
    }
}
