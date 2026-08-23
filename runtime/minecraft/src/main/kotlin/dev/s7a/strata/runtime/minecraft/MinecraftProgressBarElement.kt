@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Retained generic progress bar backed by the active Minecraft bundle sprites.
 */
internal class MinecraftProgressBarElement private constructor(
    internal val border: DrawImage,
    internal val fill: DrawImage,
    internal val full: DrawImage,
    internal val progress: Double,
    internal val size: IntSize,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = emptyList(),
        modifier = modifier,
    ) {
    /**
     * Retains progress presentation and accessibility value.
     */
    internal class Node(
        initial: MinecraftProgressBarElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        SemanticsNode {
        private var border = initial.border
        private var fill = initial.fill
        private var full = initial.full
        private var progress = initial.progress
        private var size = initial.size

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "ProgressBar cannot have children." }
            require(constraints.isSatisfiedBy(size)) { "ProgressBar constraints must contain its requested size." }
            return size
        }

        override fun paint(scope: PaintScope) {
            val innerWidth = size.width - BORDER * 2
            val completedWidth = (innerWidth.toDouble() * progress).toInt()
            if (0 < completedWidth) {
                val destination = IntRect(BORDER, BORDER, BORDER + completedWidth, size.height - BORDER)
                paintMinecraftNineSlice(
                    MinecraftRectPaintScope(scope, destination),
                    if (progress == 1.0) full else fill,
                    Insets.all(FILL_BORDER),
                    NineSliceCenterMode.Tiled,
                )
            }
            paintMinecraftNineSlice(scope, border, Insets.all(BORDER), NineSliceCenterMode.Tiled)
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(
                Semantics(
                    role = SemanticsRole.ProgressBar,
                    value = UiText.Literal("${(progress * 100.0).toInt()}%"),
                ),
            )
        }

        /**
         * Applies a changed immutable progress description.
         */
        internal fun update(current: MinecraftProgressBarElement): DirtyMask {
            val measureChanged = size != current.size
            val paintChanged = border !== current.border || fill !== current.fill || full !== current.full || progress != current.progress
            val semanticsChanged = progress != current.progress
            border = current.border
            fill = current.fill
            full = current.full
            progress = current.progress
            size = current.size
            var dirty = DirtyMask.None
            if (measureChanged) dirty += DirtyMask.of(DirtyPhase.Measure)
            if (paintChanged) dirty += DirtyMask.of(DirtyPhase.Paint)
            if (semanticsChanged) dirty += DirtyMask.of(DirtyPhase.Semantics)
            return dirty
        }
    }

    /**
     * Owns the stable retained type and verified vanilla sprite borders.
     */
    companion object {
        private const val BORDER = 2
        private const val FILL_BORDER = 2
        private val TYPE: ElementType<MinecraftProgressBarElement, Node> =
            ElementType(
                elementClass = MinecraftProgressBarElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.border.size == IntSize(12, 12)) { "ProgressBar border sprite must be 12 by 12 pixels." }
                    require(element.fill.size == IntSize(6, 6)) { "ProgressBar fill sprite must be 6 by 6 pixels." }
                    require(element.full.size == IntSize(6, 6)) { "Completed ProgressBar fill sprite must be 6 by 6 pixels." }
                    require(element.progress.isFinite() && element.progress in 0.0..1.0) { "Progress must be finite and normalized." }
                    require(BORDER * 2 < element.size.width && BORDER * 2 < element.size.height) { "ProgressBar size must leave a nonempty interior." }
                },
                createNode = { element -> Node(element) },
                updateNode = { _, current, node -> node.update(current) },
            )

        /**
         * Creates one immutable profile-backed progress bar description.
         */
        internal fun create(
            border: DrawImage,
            fill: DrawImage,
            full: DrawImage,
            progress: Double,
            size: IntSize,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftProgressBarElement(border, fill, full, progress, size, modifier, key)
    }
}
