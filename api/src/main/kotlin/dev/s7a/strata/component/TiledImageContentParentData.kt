package dev.s7a.strata.component

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameCutoffNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Active parent-data implementation for tiled-image overlay placement.
 */
internal object TiledImageContentParentData {
    /**
     * Immutable fixed or revisioned content-position description.
     */
    internal sealed interface Position {
        /**
         * One position retained directly in the modifier description.
         *
         * @property value finite content coordinate.
         */
        data class Fixed(
            val value: DoubleOffset,
        ) : Position

        /**
         * One externally owned revision history observed only while attached.
         *
         * @property source position revisions whose identity controls observation replacement.
         */
        data class Revisioned(
            val source: StateSource<DoubleOffset>,
        ) : Position
    }

    /**
     * Immutable overlay anchor in source content coordinates.
     *
     * @property position source content coordinate transformed by the owning tiled image.
     * @property alignment child alignment relative to the transformed anchor.
     */
    internal data class Data(
        val position: DoubleOffset,
        val alignment: Alignment,
    )

    /**
     * Stable referential token consumed only by the tiled-image layout.
     */
    internal val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

    /**
     * Immutable modifier description for one overlay anchor.
     *
     * @property position fixed coordinate or externally revisioned position source.
     * @property alignment child alignment relative to the committed coordinate.
     */
    internal data class Element(
        val position: Position,
        val alignment: Alignment,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained provider of one tiled-image overlay anchor.
     *
     * @param initial initial immutable placement description.
     */
    internal class Node(
        initial: Element,
    ) : ModifierNode(),
        ParentDataModifierNode<Data>,
        FrameCutoffNode,
        SessionAttachmentNode {
        private var position: Position? = initial.position
        private var alignment: Alignment = initial.alignment
        private var binding: PositionBinding? = null
        private var active: Boolean = false

        override val parentDataKey: ParentDataKey<Data>
            get() = KEY

        override fun parentData(): Data {
            val current = checkNotNull(position) { "A disposed tiled-image content position is unavailable." }
            val value =
                when (current) {
                    is Position.Fixed -> current.value
                    is Position.Revisioned -> checkNotNull(binding) { "An observed tiled-image content position is not attached." }.position()
                }
            return Data(value, alignment)
        }

        override fun attach() {
            sessionAttached()
        }

        override fun sessionAttached() {
            if (active) return
            active = true
            binding = openBinding(checkNotNull(position))
            invalidate(DirtyMask.of(DirtyPhase.Measure))
        }

        override fun captureFrameState() {
            binding?.captureFrame()
        }

        override fun commitFrameState() {
            if (binding?.commitFrame() == true) invalidate(DirtyMask.of(DirtyPhase.Measure))
        }

        override fun sessionDetached() {
            active = false
            val previous = binding
            binding = null
            previous?.close()
        }

        override fun detach() {
            sessionDetached()
        }

        override fun dispose() {
            position = null
            sessionDetached()
        }

        /**
         * Replaces the retained anchor and invalidates its consuming parent geometry.
         *
         * @param next incoming immutable placement description.
         * @return measurement invalidation only when position identity, fixed value, or alignment changed.
         */
        internal fun update(next: Element): DirtyMask {
            val previousPosition = checkNotNull(position)
            val positionChanged = samePosition(previousPosition, next.position).not()
            val changed = positionChanged || alignment != next.alignment
            position = next.position
            alignment = next.alignment
            if (positionChanged) {
                val previousBinding = binding
                binding = null
                previousBinding?.close()
                if (active) binding = openBinding(next.position)
            }
            return if (changed) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
        }

        private fun openBinding(description: Position): PositionBinding? {
            val source = (description as? Position.Revisioned)?.source ?: return null
            val opened = PositionBinding()
            return runCatching {
                opened.install(source.subscribe(opened::enqueue))
                opened
            }.getOrElse { failure ->
                val cleanupFailure = runCatching(opened::close).exceptionOrNull()
                if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                throw failure
            }
        }

        private fun samePosition(
            previous: Position,
            next: Position,
        ): Boolean =
            when (previous) {
                is Position.Fixed -> next is Position.Fixed && previous.value == next.value
                is Position.Revisioned -> next is Position.Revisioned && previous.source === next.source
            }
    }

    private class PositionBinding : AutoCloseable {
        private val monitor = Any()
        private var committed: StateSnapshot<DoubleOffset>? = null
        private var pending: StateSnapshot<DoubleOffset>? = null
        private var captured: StateSnapshot<DoubleOffset>? = null
        private var closeAction: (() -> Unit)? = null
        private var frameCaptured: Boolean = false
        private var closed: Boolean = false

        fun install(subscription: StateSubscription<DoubleOffset>) {
            val initial = subscription.initialSnapshot
            val action = subscription.retainCloseAction()
            synchronized(monitor) {
                check(closed.not()) { "A closed tiled-image content position cannot install a subscription." }
                closeAction = action
                committed = initial
                if (pending?.revision?.let { revision -> revision <= initial.revision } == true) pending = null
            }
        }

        fun enqueue(snapshot: StateSnapshot<DoubleOffset>) {
            synchronized(monitor) {
                if (closed) return
                val committedRevision = committed?.revision
                val capturedRevision = captured?.revision
                val pendingRevision = pending?.revision
                if (committedRevision != null && snapshot.revision <= committedRevision) return
                if (capturedRevision != null && snapshot.revision <= capturedRevision) return
                if (pendingRevision != null && snapshot.revision <= pendingRevision) return
                pending = snapshot
            }
        }

        fun captureFrame() {
            synchronized(monitor) {
                check(closed.not()) { "A closed tiled-image content position cannot capture a frame." }
                check(frameCaptured.not()) { "A tiled-image content-position frame is already captured." }
                captured = pending
                pending = null
                frameCaptured = true
            }
        }

        fun commitFrame(): Boolean =
            synchronized(monitor) {
                check(frameCaptured) { "A tiled-image content-position frame must be captured before commit." }
                frameCaptured = false
                val next = captured
                captured = null
                if (closed || next == null) return@synchronized false
                committed = next
                true
            }

        fun position(): DoubleOffset = synchronized(monitor) { checkNotNull(committed).value }

        override fun close() {
            val action =
                synchronized(monitor) {
                    if (closed) return
                    closed = true
                    committed = null
                    pending = null
                    captured = null
                    frameCaptured = false
                    val retained = closeAction
                    closeAction = null
                    retained
                }
            action?.invoke()
        }
    }

    /**
     * Stable modifier token for tiled-image overlay anchors.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
