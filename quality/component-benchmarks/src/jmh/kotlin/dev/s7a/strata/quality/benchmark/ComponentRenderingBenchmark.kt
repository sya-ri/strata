package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
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
 * Independent all-component corpus using the real profile host and shipped declarations.
 * JMH owns sampling, warm-up, forks, allocation and GC profiling; no consumer clock or sample loop is added.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ComponentRenderingBenchmark {
    /**
     * Returns a clean retained frame with unchanged input and explicit stable viewport.
     */
    @Benchmark
    public fun idle(state: ComponentSession): RuntimeUiFrame = state.idle()

    /**
     * Alternates actual viewport constraints on the same host.
     */
    @Benchmark
    public fun resize(state: ComponentSession): RuntimeUiFrame = state.resize()

    /**
     * Delivers a real pointer move and extracts the resulting frame; controls may legitimately ignore it.
     */
    @Benchmark
    public fun pointer(state: ComponentSession): RuntimeUiFrame = state.pointer()

    /**
     * Includes one-shot definition creation, attach, first frame and terminal release in each operation.
     */
    @Benchmark
    public fun lifecycle(state: ComponentSession): RuntimeUiFrame = state.lifecycle()

    /**
     * One profile and retained host per owner worker, with optional untimed deterministic work monitoring.
     */
    @State(Scope.Thread)
    public open class ComponentSession {
        /**
         * Real shipped component selected by JMH, including controls absent from downstream applications.
         */
        @JvmField
        @Param
        public var component: ComponentWorkload = ComponentWorkload.Row

        private lateinit var profile: MinecraftUiProfile
        private lateinit var host: MinecraftUiHost
        private var monitor: RuntimeWorkMonitor? = null
        private var revision = 0
        private val viewport = IntSize(320, 240)

        /**
         * Builds immutable synthetic inputs and primes one retained frame outside measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            profile = ComponentProfile.create()
            host = createMinecraftUiHost(definition(), profile, fontBackend = LwjglMinecraftFontBackendFactory)
            host.attach()
            host.frame(viewport)
        }

        /**
         * Enables bounded monitoring only for deterministic checks, leaving JMH's workload untouched.
         */
        public fun monitorWork() {
            check(monitor == null)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 64)
        }

        /**
         * Extracts the current clean frame.
         */
        public fun idle(): RuntimeUiFrame = measured { host.frame(viewport) }

        /**
         * Alternates a one-pixel viewport change to invalidate retained layout.
         */
        public fun resize(): RuntimeUiFrame {
            revision += 1
            return measured { host.frame(IntSize(320 + revision % 2, 240 + revision % 2)) }
        }

        /**
         * Alternates pointer positions inside the real logical component frame.
         */
        public fun pointer(): RuntimeUiFrame {
            revision += 1
            return measured {
                host.dispatchPointer(PointerEvent.Move(IntOffset(20 + revision % 2, 20)))
                host.frame(viewport)
            }
        }

        /**
         * Returns a detached frame after the fresh host has already released all of its resources.
         */
        public fun lifecycle(): RuntimeUiFrame =
            createMinecraftUiHost(definition(), profile, fontBackend = LwjglMinecraftFontBackendFactory).use { fresh ->
                fresh.attach()
                fresh.frame(viewport)
            }

        /**
         * Returns detached work for assertions, with no retained prior frame inventory.
         */
        public fun snapshot(): JsonObject = checkNotNull(monitor).snapshot()

        private inline fun measured(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()

        @Suppress("DEPRECATION") // The fixture adapter transfers each shipped one-shot compatibility definition exactly once.
        private fun definition(): UiDefinition {
            val payload = component.definition().transfer()
            // The outer layout supplies loose child constraints so fixed-size list declarations remain valid during host resize.
            return UiDefinition(payload.title, pausesGame = payload.pausesGame) { Stack { payload.content(this) } }
        }

        /**
         * Closes monitoring and the retained host on its owner worker, including assertion failure paths.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                host.close()
            }
        }
    }
}
