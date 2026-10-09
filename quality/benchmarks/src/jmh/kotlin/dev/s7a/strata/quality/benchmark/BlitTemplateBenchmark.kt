package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * Measures actual core template construction through complete initial frames, retained paint rebuilds and clean frames.
 * Sources and original placements are frozen outside timing; pixels, admission, old-frame immutability and binding release are checked separately.
 * These CPU boundaries include their declared session/paint work and establish no native upload, GPU or FPS result.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class BlitTemplateBenchmark {
    /**
     * Creates, attaches, paints and closes a fresh actual session, returning its detached first frame.
     */
    @Benchmark
    public fun initialFrame(state: Scene): RuntimeUiFrame = state.initial()

    /**
     * Changes the committed immutable source and paints the existing session through its ordinary frame cutoff.
     */
    @Benchmark
    public fun paintRebuild(state: Scene): RuntimeUiFrame = state.rebuild()

    /**
     * Returns the primed actual retained frame without another source change or template construction.
     */
    @Benchmark
    public fun cleanFrame(state: Scene): RuntimeUiFrame = state.clean()

    /**
     * Complete small/dense/atlas and ineligible controls; multiple atlas rectangles still belong to one source image.
     */
    public enum class Case(
        public val sourceExtent: Int = 2,
        public val columns: Int = 8,
        public val rows: Int = 8,
        public val groups: Int = 1,
        public val compacts: Boolean = true,
    ) {
        Small,
        Moderate(sourceExtent = 4),
        Large(sourceExtent = 4, columns = 32, rows = 32),
        PartialEdges(sourceExtent = 3, columns = 23, rows = 25),
        LargeSource(sourceExtent = 16, columns = 32, rows = 32),
        Atlas4(columns = 16, rows = 16, groups = 4),
        Atlas32(columns = 32, rows = 32, groups = 32),
        Sparse(columns = 32, rows = 32, compacts = false),
        Overlapping(compacts = false),
        Reversed(compacts = false),
        Stretched(compacts = false),
        OversizedSource(sourceExtent = 65, compacts = false),
        MixedImages(compacts = false),
        Clipped(compacts = false),
        FractionalTransform(compacts = false),
        ;

        /**
         * Local canvas bounds covering every original cell, including stretched and multi-rectangle grids.
         */
        public val extent: IntSize
            get() {
                val cell = sourceExtent * if (this == Stretched) 2 else 1
                return IntSize(minOf(groups, 8) * columns * cell, (groups + 7) / 8 * rows * cell)
            }

        /**
         * Actual parent transform; fractional presentation must use originals without materializing templates.
         */
        public val transform: ChildTransform
            get() = if (this == FractionalTransform) ChildTransform(0.5, DoubleOffset(0.25, 0.75)) else ChildTransform.Identity

        /**
         * Positive root viewport enclosing the transformed canvas.
         */
        public val viewport: IntSize
            get() = IntSize(ceil(extent.width * transform.scale + transform.offset.x).toInt(), ceil(extent.height * transform.scale + transform.offset.y).toInt())
    }

    /**
     * One worker-owned prepared source pair and retained session; no source creation or fixture rasterization occurs in samples.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * All compiled cases use the same original geometry on each supplied actual runtime.
         */
        @JvmField
        @Param
        public var case: Case = Case.Small

        private lateinit var originals: List<List<DrawCommand.BlitImage>>
        private lateinit var constraints: Constraints
        private var session: RuntimeUiSession? = null
        private var revision = 0
        private var opened = 0
        private var closed = 0
        private var paints = 0

        /**
         * Prepares immutable sources, primes the real session and verifies changed/clean pixels and ownership outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            originals = List(2, ::commands)
            constraints = Constraints.fixed(case.viewport.width, case.viewport.height)
            session = create()
            val owner = checkNotNull(session)
            owner.attach()
            val first = owner.frame(constraints)
            verify(first, revision)
            val saved = first.drawCommands.mapNotNull(::image).map { it to it.copyArgb() }
            val changed = rebuild()
            verify(changed, revision)
            check(clean() === changed)
            val before = paints
            repeat(10) { check(clean() === changed) }
            check(paints == before)
            saved.forEach { (image, pixels) -> check(image.copyArgb().contentEquals(pixels)) }
            verify(initial(), revision)
            check(opened - closed == 1)
        }

        /**
         * Includes actual first-frame and terminal session work with sources already prepared.
         */
        public fun initial(): RuntimeUiFrame =
            create().use { owner ->
                owner.attach()
                owner.frame(constraints)
            }

        /**
         * Alternates the external revision before capture; the existing attachment performs one ordinary repaint.
         */
        public fun rebuild(): RuntimeUiFrame {
            revision = 1 - revision
            return checkNotNull(session).frame(constraints)
        }

        /**
         * Uses the same constraints and committed source, with no invalidation.
         */
        public fun clean(): RuntimeUiFrame = checkNotNull(session).frame(constraints)

        /**
         * Releases the real binding/session and drops current runtime references even when verification fails.
         */
        @TearDown(Level.Trial)
        public fun close() {
            val owner = session
            session = null
            owner?.close()
            check(opened == closed)
        }

        /**
         * Captures actual untimed frame/template work, without inferring private copies or replacing a timing receipt.
         */
        internal fun work(): JsonObject {
            val frame = clean()
            val templates = templates(frame, originals[revision])
            return JsonObject().apply {
                addProperty("case", case.name)
                addProperty("original_commands", originals[revision].size)
                addProperty("presented_commands", frame.drawCommands.size)
                addProperty("template_images", templates.size)
                addProperty("template_pixels", templates.sumOf { it.size.width * it.size.height })
                addProperty("paint_calls", paints)
                addProperty("bindings_opened", opened)
                addProperty("bindings_closed", closed)
            }
        }

        private fun create(): RuntimeUiSession =
            createRuntimeUiSession {
                val source =
                    CanvasSource {
                        opened += 1
                        object : CanvasBinding {
                            private var captured = revision
                            private var committed = revision
                            private var released = false

                            override fun captureFrame() {
                                captured = revision
                            }

                            override fun commitFrame(): Boolean {
                                val changed = captured != committed
                                committed = captured
                                return changed
                            }

                            override fun paint(scope: PaintScope) {
                                paints += 1
                                if (case == Case.Clipped) {
                                    scope.withClip(IntRect(1, 1, case.extent.width - 1, case.extent.height - 1)) { emit(scope, committed) }
                                } else {
                                    emit(scope, committed)
                                }
                            }

                            override fun close() {
                                if (released.not()) {
                                    released = true
                                    closed += 1
                                }
                            }
                        }
                    }
                ParentElement(case, evaluateComponentTree { Canvas(source, case.extent) })
            }

        private fun commands(generation: Int): List<DrawCommand.BlitImage> {
            val size = IntSize(case.sourceExtent * case.groups, case.sourceExtent)
            val alphas = intArrayOf(0, 128, 255)
            val source = createDrawImage(size) { x, y -> (alphas[(x + y) % 3] shl 24) or ((x * 7919 + y * 3571 + generation * 19937) and 0xFFFFFF) }
            val other = if (case == Case.MixedImages) createDrawImage(size, source.copyArgb()) else source
            val cell = case.sourceExtent * if (case == Case.Stretched) 2 else 1
            val columns = minOf(case.groups, 8)
            val commands =
                (0 until case.groups).flatMap { group ->
                    val slice = IntRect(group * case.sourceExtent, 0, (group + 1) * case.sourceExtent, case.sourceExtent)
                    List(case.columns * case.rows) { index ->
                        val x = group % columns * case.columns * cell + index % case.columns * cell
                        val y = group / columns * case.rows * cell + index / case.columns * cell
                        DrawCommand.BlitImage(if (index % 2 == 0) source else other, slice, IntRect(x, y, x + cell, y + cell))
                    }
                }
            return when (case) {
                Case.Sparse -> commands.dropLast(1)
                Case.Overlapping -> commands.dropLast(1) + commands.first()
                Case.Reversed -> commands.reversed()
                else -> commands
            }
        }

        private fun emit(
            scope: PaintScope,
            generation: Int,
        ) {
            originals[generation].forEach { scope.blitImage(it.image, it.source, it.destination) }
        }

        private fun verify(
            frame: RuntimeUiFrame,
            generation: Int,
        ) {
            check(frame.size == case.viewport && frame.semantics.isEmpty())
            val templates = templates(frame, originals[generation])
            check(templates.size == if (case.compacts) case.groups else 0)
            check(templates.all { it.size.width <= 64 && it.size.height <= 64 })
            templates.forEach { template ->
                val detached = createDrawImage(template.size, template.copyArgb())
                check(template == detached && template.hashCode() == detached.hashCode())
                val copied = template.copyArgb()
                copied.fill(0)
                check(template == detached)
            }
            val reference = reference(generation)
            for (density in listOf(1, 2)) {
                check(rasterizeHeadless(frame.drawCommands, case.viewport, density).copyArgb().contentEquals(rasterizeHeadless(reference, case.viewport, density).copyArgb()))
            }
        }

        private fun reference(generation: Int): List<DrawCommand> {
            val commands: List<DrawCommand> =
                if (case == Case.FractionalTransform) {
                    originals[generation].map { command ->
                        val source = command.source
                        val destination = command.destination
                        val transform = case.transform
                        DrawCommand.SampledImage(
                            command.image,
                            FloatRect(source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat()),
                            FloatRect((destination.left * transform.scale + transform.offset.x).toFloat(), (destination.top * transform.scale + transform.offset.y).toFloat(), (destination.right * transform.scale + transform.offset.x).toFloat(), (destination.bottom * transform.scale + transform.offset.y).toFloat()),
                            alphaCutoff = 0f,
                        )
                    }
                } else {
                    originals[generation]
                }
            return if (case == Case.Clipped) listOf(DrawCommand.PushClip(IntRect(1, 1, case.extent.width - 1, case.extent.height - 1))) + commands + DrawCommand.PopClip else commands
        }
    }

    /**
     * Complete fixture admission, pixel/copy and clean/repaint/lifetime checks outside collection.
     */
    public companion object {
        /**
         * Checks all 45 compiled boundaries through the same actual runtime used by subsequent JMH forks.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(BlitTemplateBenchmark::class.java), setOf("avgt")).size == 45)
            for (case in Case.entries) {
                val scene = Scene()
                scene.case = case
                try {
                    scene.setup()
                } finally {
                    scene.close()
                }
            }
        }

        /**
         * Writes fresh actual work/provenance diagnostics; no timing, private-copy count or CPU receipt contract is synthesized.
         */
        @JvmStatic
        public fun main(args: Array<String>) {
            require(args.size == 1)
            val rows = JsonArray()
            Case.entries.forEach { case ->
                val scene = Scene()
                scene.case = case
                try {
                    scene.setup()
                    rows.add(scene.work())
                } finally {
                    scene.close()
                }
            }
            PerformanceJson.writeNew(
                Path.of(args.single()),
                JsonObject().apply {
                    addProperty("diagnostic", "blit-template-work-v1")
                    addProperty("fixture_archive_sha256", ArtifactIdentity.fullCodeSource(BlitTemplateBenchmark::class.java))
                    add(
                        "runtime",
                        LoadedArtifactMetadata
                            .capture(
                                BlitTemplateBenchmark::class.java.classLoader,
                                mapOf("api" to "dev.s7a.strata.render.DrawImage", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage"),
                                setOf("api", "core", "headless"),
                            ).also(LoadedArtifactMetadata::verifyComplete),
                    )
                    add("cases", rows)
                },
            )
        }

        private fun templates(
            frame: RuntimeUiFrame,
            originals: List<DrawCommand.BlitImage>,
        ): Set<DrawImage> {
            val templates = Collections.newSetFromMap(IdentityHashMap<DrawImage, Boolean>())
            val sources = Collections.newSetFromMap(IdentityHashMap<DrawImage, Boolean>())
            originals.forEach { sources.add(it.image) }
            frame.drawCommands.mapNotNull(::image).forEach { if ((it in sources).not()) templates.add(it) }
            return templates
        }

        private fun image(command: DrawCommand): DrawImage? =
            when (command) {
                is DrawCommand.BlitImage -> command.image
                is DrawCommand.SampledImage -> command.image
                else -> null
            }
    }

    /**
     * Ordinary parent geometry applies the actual core child-transform gate before paint is flattened.
     */
    private class ParentNode(
        private val case: Case,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ChildTransformNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, Constraints.fixed(case.extent.width, case.extent.height))
            return constraints.constrain(case.viewport)
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset.Zero)
        }

        override fun childTransform(index: Int): ChildTransform = case.transform
    }

    /**
     * One real Canvas child; no fixture-specific primitive is admitted to the public component set.
     */
    private class ParentElement(
        val case: Case,
        child: Element,
    ) : Element(ElementIdentity.Positional, TYPE, children = listOf(child)) {
        companion object {
            val TYPE: ElementType<ParentElement, ParentNode> =
                ElementType(
                    elementClass = ParentElement::class,
                    nodeClass = ParentNode::class,
                    validateLocal = { _ -> },
                    createNode = { ParentNode(it.case) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
