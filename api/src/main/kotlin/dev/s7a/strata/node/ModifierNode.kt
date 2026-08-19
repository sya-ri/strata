package dev.s7a.strata.node

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope

/**
 * Base retained node for one active modifier description.
 *
 * A creation hook returns a fresh instance, and the runtime owns it through detach and dispose after binding.
 * Constructors initialize ordinary data; lifecycle callbacks acquire and release external resources.
 * The default behavior is a one-child pass-through measure and layout.
 * A modifier scope always exposes exactly one virtual child.
 * An override may leave that child unmeasured or unplaced, which excludes the component subtree from later phases.
 * Nodes are thread-confined by the owning tree.
 * Subclasses implement only the typed capability interfaces whose behavior they provide.
 */
public abstract class ModifierNode :
    Node(),
    MeasureNode,
    LayoutNode {
    /**
     * Measures the one virtual child under unchanged constraints.
     *
     * @param scope the modifier's one-child measurement scope.
     * @param constraints the incoming constraints.
     * @return the child's measured size.
     * @throws Throwable when the scope or child measurement fails.
     */
    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        require(scope.childCount == 1) { "A modifier must have exactly one virtual child." }
        return scope.measureChild(0, constraints)
    }

    /**
     * Places the one virtual child at the modifier origin.
     *
     * @param scope the modifier's one-child layout scope.
     * @throws Throwable when the scope rejects the placement.
     */
    override fun layout(scope: LayoutScope) {
        require(scope.childCount == 1) { "A modifier must have exactly one virtual child." }
        scope.placeChild(0, IntOffset.Zero)
    }
}
