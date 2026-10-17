package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.LoadingIndicator
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.size
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
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
 * Actual stress operations beyond showcase pointer motion, with sampling and forks owned entirely by JMH.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class StressRenderingBenchmark {
    /**
     * Reuses the current retained frame with a fixed explicit animation timestamp.
     */
    @Benchmark
    public fun idle(state: StressSession): RuntimeUiFrame = state.idle()

    /**
     * Publishes input, scroll, image, layout or host-time changes and extracts one real frame.
     */
    @Benchmark
    public fun update(state: StressSession): RuntimeUiFrame = state.update()

    /**
     * Creates, attaches, extracts and terminally closes an independent host sharing immutable inputs.
     */
    @Benchmark
    public fun lifecycle(state: StressSession): RuntimeUiFrame = state.lifecycle()

    /**
     * Worker-owned retained fixture; resource and pixel preparation are outside measured operations.
     */
    @State(Scope.Thread)
    public open class StressSession {
        /**
         * Exact compiled stress input registered by JMH.
         */
        @JvmField
        @Param
        public var workload: StressWorkload = StressWorkload.VirtualList100

        private lateinit var profile: MinecraftUiProfile
        private lateinit var host: MinecraftUiHost
        private lateinit var source: StressStateSource<Int>
        private lateinit var pixels: StressStateSource<DrawImage>
        private lateinit var images: List<DrawImage>
        private lateinit var field: TextFieldState
        private lateinit var texts: List<String>
        private lateinit var checkbox: CheckboxState
        private lateinit var navigation: VirtualListState<Int>
        private var monitor: RuntimeWorkMonitor? = null
        private var revision = 0
        private val viewport = IntSize(320, 240)

        /**
         * Prepares immutable assets and one initial retained frame without profiling their acquisition.
         */
        @Setup(Level.Trial)
        public fun setup() {
            profile = ComponentProfile.create()
            source = StressStateSource(0)
            images = prepareImages()
            pixels = StressStateSource(images.first())
            val length = if (workload == StressWorkload.TextField16384) 16_384 else 32
            texts = listOf("a", "b").map { prefix -> prefix + "abc日本語".repeat((length + 5) / 6).take(length - 1) }
            check(texts.all { it.length == length })
            field = TextFieldState(texts.first(), maxLength = length)
            checkbox = CheckboxState()
            navigation = VirtualListState()
            host = fresh()
            host.attach()
            host.frame(viewport, FrameTime(0))
        }

        /**
         * Optional bounded diagnostic collection used only by untimed correctness checks.
         */
        public fun monitorWork() {
            check(monitor == null)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 64, maxNodeRecords = 16_384)
        }

        /**
         * Extracts a clean frame without advancing animation or changing the application input.
         */
        public fun idle(): RuntimeUiFrame = measured { host.frame(viewport, time()) }

        /**
         * Changes the actual state consumed by this workload; it does not simulate changes through private fields.
         */
        public fun update(): RuntimeUiFrame =
            measured {
                revision += 1
                when (workload) {
                    StressWorkload.VirtualList100, StressWorkload.VirtualList1000000 -> {
                        check(navigation.jumpToIndex(if (revision % 2 == 0) 0 else count - 1))
                    }

                    StressWorkload.Canvas256, StressWorkload.Canvas1024 -> {
                        pixels.publish(images[revision % images.size])
                    }

                    StressWorkload.TextField32, StressWorkload.TextField16384 -> {
                        field.value = texts[revision % 2]
                    }

                    StressWorkload.Checkbox -> {
                        host.dispatchPointer(PointerEvent.Press(IntOffset(4, 4), PointerButton.Primary))
                        host.dispatchPointer(PointerEvent.Release(IntOffset(4, 4), PointerButton.Primary))
                    }

                    StressWorkload.Animation -> {}

                    else -> {
                        source.publish(revision)
                    }
                }
                host.frame(viewport, time())
            }

        /**
         * Returns a detached frame after an independent host has released its complete attachment.
         */
        public fun lifecycle(): RuntimeUiFrame =
            fresh(independent = true).use { next ->
                next.attach()
                next.frame(viewport, time())
            }

        /**
         * Detaches complete interval diagnostics for the shared work assertions.
         */
        public val diagnostics: JsonObject get() = checkNotNull(monitor).snapshot()

        /**
         * Exposes fixture ownership and real input state to untimed assertions.
         */
        public val subscriptions: Int get() = source.subscriptions + pixels.subscriptions

        /**
         * Returns the public control value, proving pointer input has reached the retained control.
         */
        public val checked: Boolean get() = checkbox.checked

        /**
         * Checks tiled source pixels against an independent modulo-based image outside measurement.
         * The oracle keeps borders, shortened final tiles and source alpha without fixing a command strategy.
         */
        public fun verifyNineSlicePixels(frame: RuntimeUiFrame) {
            val extent = repeatExtent ?: return
            if (extent == 1) check(frame.drawCommands.isNotEmpty() && frame.drawCommands.size <= 9) { "The one-pixel repeated center must keep bounded drawing work" }
            val design = IntSize(319 + revision % 2, 239)
            val image = images.first()

            fun sourceCoordinate(
                position: Int,
                destination: Int,
                sourceExtent: Int,
            ): Int =
                when (position) {
                    0 -> 0
                    destination - 1 -> sourceExtent - 1
                    else -> 1 + (position - 1) % (sourceExtent - 2)
                }
            val expected =
                createDrawImage(
                    design,
                    IntArray(design.width * design.height) { index ->
                        image.argbAt(
                            sourceCoordinate(index % design.width, design.width, image.size.width),
                            sourceCoordinate(index / design.width, design.height, image.size.height),
                        )
                    },
                )
            createMinecraftUiHost(
                UiDefinition("Nine-slice pixel oracle") { Stack { Image(ImageSource.Pixels(expected), size = design) } },
                profile,
                fontBackend = LwjglMinecraftFontBackendFactory,
            ).use { oracle ->
                oracle.attach()
                val actualPixels = rasterizeHeadless(frame.drawCommands, viewport).copyArgb()
                val expectedPixels = rasterizeHeadless(oracle.frame(viewport).drawCommands, viewport).copyArgb()
                check(actualPixels.contentEquals(expectedPixels)) { "Nine-slice source pattern changed: $workload at $design" }
            }
        }

        private fun time(): FrameTime = FrameTime(revision.toLong() * 300_000_000L)

        private val count: Int get() = if (workload == StressWorkload.VirtualList1000000) 1_000_000 else 100

        private val repeatExtent: Int?
            get() =
                when (workload) {
                    StressWorkload.NineSlice1 -> 1
                    StressWorkload.NineSlice2 -> 2
                    StressWorkload.NineSlice4 -> 4
                    else -> null
                }

        private fun prepareImages(): List<DrawImage> {
            val extent =
                when (workload) {
                    StressWorkload.Canvas256 -> 256
                    StressWorkload.Canvas1024 -> 1024
                    else -> repeatExtent?.plus(2) ?: 1
                }
            return List(if (workload in setOf(StressWorkload.Canvas256, StressWorkload.Canvas1024)) 8 else 1) { phase ->
                createDrawImage(
                    IntSize(extent, extent),
                    IntArray(extent * extent) { pixel ->
                        if ((pixel + phase) % 2 == 0) 0x80426789.toInt() + phase else 0xFF987654.toInt() - phase
                    },
                )
            }
        }

        private fun fresh(independent: Boolean = false): MinecraftUiHost {
            // Independent lifecycle hosts must not claim the existing retained list/editor state.
            val editor = if (independent) TextFieldState(field.value, field.maxLength) else field
            val list = if (independent) VirtualListState<Int>() else navigation
            return createMinecraftUiHost(
                UiDefinition("Stress ${workload.name}") {
                    Stack {
                        when (workload) {
                            StressWorkload.VirtualList100, StressWorkload.VirtualList1000000 -> {
                                VirtualList(count, { it }, { it }, list, IntSize(240, 120), 12, indexOfKey = { it }) { item -> Text("Row $item") }
                            }

                            StressWorkload.Canvas256, StressWorkload.Canvas1024 -> {
                                Canvas(canvasSource(pixels), IntSize(319, 239))
                            }

                            StressWorkload.TextField32, StressWorkload.TextField16384 -> {
                                TextField(editor, IntSize(300, 20))
                            }

                            StressWorkload.Checkbox -> {
                                Checkbox("Selected", checkbox)
                            }

                            StressWorkload.Animation -> {
                                LoadingIndicator()
                            }

                            StressWorkload.FanOut128, StressWorkload.FanOut4096 -> {
                                repeat(if (workload == StressWorkload.FanOut4096) 4096 else 128) { Observe(source) { value -> Spacer(Modifier.size(1 + value % 2, 1)) } }
                            }

                            StressWorkload.NineSlice1, StressWorkload.NineSlice2, StressWorkload.NineSlice4 -> {
                                Observe(source) { value ->
                                    Spacer(Modifier.size(319 + value % 2, 239).imageBackground(ImageSource.Pixels(images.first()), Insets.all(1), NineSliceCenterMode.Tiled))
                                }
                            }
                        }
                    }
                },
                profile,
                fontBackend = LwjglMinecraftFontBackendFactory,
            )
        }

        private inline fun measured(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()

        /**
         * Releases monitor ownership and every retained subscription on the JMH worker, including assertion failures.
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
