package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures retained core frames and portable headless rasterization through Strata's public runtime contracts.
 *
 * JMH owns benchmark and state instances.
 * Every session is constructed, used, and closed on its JMH worker thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class RenderingBenchmark {
    /**
     * Produces a frame whose retained component and modifier caches are clean.
     *
     * @param state thread-confined session fixture prepared by JMH.
     * @return the detached immutable frame so JMH observes the result.
     */
    @Benchmark
    public fun cleanUiSessionFrame(state: SessionState): RuntimeUiFrame = state.cleanFrame()

    /**
     * Produces a time-advanced frame whose retained scene has no time-dependent invalidation.
     *
     * This is the clean path used by a platform host that supplies a timestamp on every render extraction.
     *
     * @param state thread-confined session fixture prepared by JMH.
     * @return the detached immutable frame so JMH observes the result.
     */
    @Benchmark
    public fun cleanTimedUiSessionFrame(state: SessionState): RuntimeUiFrame = state.cleanTimedFrame()

    /**
     * Invalidates every leaf before producing a retained frame.
     *
     * @param state thread-confined session fixture prepared by JMH.
     * @return the detached immutable frame so JMH observes the result.
     */
    @Benchmark
    public fun dirtyUiSessionFrame(state: SessionState): RuntimeUiFrame = state.dirtyFrame()

    /**
     * Rasterizes a detached display list into a new headless image.
     *
     * @param state thread-confined raster fixture prepared by JMH.
     * @return the newly allocated immutable image so JMH observes the result.
     */
    @Benchmark
    public fun headlessRasterization(state: RasterState): HeadlessImage = state.rasterize()

    /**
     * Representative logical viewport sizes used by every benchmark category.
     *
     * @property size positive logical viewport passed to the core or headless facade.
     */
    public enum class Viewport(
        public val size: IntSize,
    ) {
        /**
         * Compact 16:9 UI viewport used by shipped headless examples.
         */
        Compact(IntSize(320, 180)),

        /**
         * Representative windowed Minecraft viewport.
         */
        Windowed(IntSize(854, 480)),

        /**
         * Representative full-HD viewport.
         */
        FullHd(IntSize(1920, 1080)),
    }

    /**
     * Owns one retained session per JMH worker thread.
     *
     * Setup performs the initial frame so measured clean invocations start with populated caches.
     * Teardown closes the session on the same worker thread.
     */
    @State(Scope.Thread)
    public open class SessionState {
        /**
         * Viewport selected and injected by JMH before trial setup.
         */
        @JvmField
        @Param
        public var viewport: Viewport = Viewport.Compact

        private lateinit var constraints: Constraints
        private lateinit var nodes: List<TileNode>
        private lateinit var session: RuntimeUiSession
        private val frameTime: FrameTime = FrameTime(1_000_000_000L)

        /**
         * Creates, attaches, and primes the retained session on its owner thread.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            val scene = Scene.create()
            constraints = Constraints.fixed(viewport.size.width, viewport.size.height)
            nodes = scene.nodes
            session = createRuntimeUiSession { scene.root }
            session.attach()
            session.frame(constraints)
        }

        /**
         * Produces a frame without invalidating the retained tree.
         *
         * @return the detached immutable frame.
         */
        public fun cleanFrame(): RuntimeUiFrame = session.frame(constraints)

        /**
         * Advances the retained tree with one stable host timestamp without invalidating any node.
         *
         * @return the detached immutable frame.
         */
        public fun cleanTimedFrame(): RuntimeUiFrame = session.frame(constraints, frameTime)

        /**
         * Invalidates every representative leaf and produces the resulting frame.
         *
         * @return the detached immutable frame after all dirty phases run.
         */
        public fun dirtyFrame(): RuntimeUiFrame {
            nodes.forEach(TileNode::invalidateAll)
            return session.frame(constraints)
        }

        /**
         * Releases the retained tree and its content capture graph on the owner thread.
         */
        @TearDown(Level.Trial)
        public fun tearDown() {
            session.close()
        }
    }

    /**
     * Owns one detached core display list per JMH worker thread.
     *
     * Setup obtains commands through a real retained session and closes that temporary session before measurement.
     */
    @State(Scope.Thread)
    public open class RasterState {
        /**
         * Viewport selected and injected by JMH before trial setup.
         */
        @JvmField
        @Param
        public var viewport: Viewport = Viewport.Compact

        private lateinit var commands: List<DrawCommand>

        /**
         * Produces and detaches the representative display list before measurement.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            val scene = Scene.create()
            commands =
                createRuntimeUiSession { scene.root }.use { temporarySession ->
                    temporarySession.attach()
                    temporarySession
                        .frame(Constraints.fixed(viewport.size.width, viewport.size.height))
                        .drawCommands
                }
        }

        /**
         * Rasterizes the detached display list into fresh pixel storage.
         *
         * @return newly allocated immutable headless image.
         */
        public fun rasterize(): HeadlessImage = rasterizeHeadless(commands, viewport.size)
    }

    private class Scene(
        val root: Element,
        val nodes: List<TileNode>,
    ) {
        companion object {
            fun create(): Scene {
                val nodes =
                    List(TILE_COUNT) { index ->
                        TileNode(
                            size = TILE_SIZE,
                            color = tileColor(index),
                            semantics = Semantics(label = UiText.Literal("Tile ${index + 1}"), role = SemanticsRole.Button),
                        )
                    }
                val root =
                    evaluateComponentTree {
                        Stack(
                            modifier = Modifier.Empty.fillMaxSize().background(BACKGROUND_COLOR),
                            contentAlignment = Alignment.Center,
                        ) {
                            Grid(
                                columns = TILE_COLUMNS,
                                horizontalSpacing = TILE_SPACING,
                                verticalSpacing = TILE_SPACING,
                            ) {
                                nodes.forEachIndexed { index, node ->
                                    element(
                                        TileElement(
                                            identity = ElementIdentity.Keyed(ElementKey(index)),
                                            size = TILE_SIZE,
                                            color = tileColor(index),
                                            semantics = Semantics(label = UiText.Literal("Tile ${index + 1}"), role = SemanticsRole.Button),
                                            node = node,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                return Scene(root, nodes)
            }

            private fun tileColor(index: Int): ArgbColor {
                val red = 48 + index * 17 % 160
                val green = 64 + index * 29 % 144
                val blue = 80 + index * 41 % 128
                return ArgbColor((0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
    }

    private class TileElement(
        identity: ElementIdentity,
        val size: IntSize,
        val color: ArgbColor,
        val semantics: Semantics,
        val node: TileNode,
    ) : Element(identity = identity, type = TYPE) {
        companion object {
            val TYPE: ElementType<TileElement, TileNode> =
                ElementType(
                    elementClass = TileElement::class,
                    nodeClass = TileNode::class,
                    validateLocal = { element ->
                        require(0 < element.size.width) { "Tile width must be positive." }
                        require(0 < element.size.height) { "Tile height must be positive." }
                    },
                    createNode = TileElement::node,
                    updateNode = { previous, current, node -> node.update(previous, current) },
                )
        }
    }

    private class TileNode(
        private var size: IntSize,
        private var color: ArgbColor,
        private var semantics: Semantics,
    ) : Node(),
        MeasureNode,
        PaintNode,
        SemanticsNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(size)

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(semantics)
        }

        fun invalidateAll() {
            invalidate(DirtyMask.All)
        }

        fun update(
            previous: TileElement,
            current: TileElement,
        ): DirtyMask {
            val measureDirty = if (previous.size != current.size) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
            val paintDirty = if (previous.color != current.color) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
            val semanticsDirty = if (previous.semantics != current.semantics) DirtyMask.of(DirtyPhase.Semantics) else DirtyMask.None
            val dirty = measureDirty + paintDirty + semanticsDirty
            size = current.size
            color = current.color
            semantics = current.semantics
            return dirty
        }
    }

    private companion object {
        val BACKGROUND_COLOR: ArgbColor = ArgbColor(0xFF20242A.toInt())
        val TILE_SIZE: IntSize = IntSize(12, 12)
        const val TILE_COLUMNS: Int = 9
        const val TILE_COUNT: Int = 54
        const val TILE_SPACING: Int = 2
    }
}
