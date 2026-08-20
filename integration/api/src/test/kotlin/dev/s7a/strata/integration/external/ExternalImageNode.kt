package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * A third-party retained node that emits an image command through [PaintScope].
 *
 * @param probe the caller-owned lifecycle observation sink.
 * @param id the typed lifecycle identity used by the test sink.
 * @property image the immutable source image retained by the node.
 * @property source the nonempty source rectangle in image coordinates.
 * @property destination the nonempty local destination rectangle.
 */
public class ExternalImageNode internal constructor(
    private val probe: ExternalProbe,
    private val id: ExternalNodeId,
    internal var image: DrawImage,
    internal var source: IntRect,
    internal var destination: IntRect,
) : Node(),
    MeasureNode,
    LayoutNode,
    PaintNode,
    LifecycleNode {
    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize = constraints.constrain(IntSize(4, 4))

    override fun layout(scope: LayoutScope): Unit = Unit

    override fun paint(scope: PaintScope) {
        scope.blitImage(image, source, destination)
    }

    override fun attach() {
        probe.lifecycle += ExternalLifecycleEvent.Attach(id)
    }

    override fun detach() {
        probe.lifecycle += ExternalLifecycleEvent.Detach(id)
    }

    override fun dispose() {
        probe.lifecycle += ExternalLifecycleEvent.Dispose(id)
    }
}
