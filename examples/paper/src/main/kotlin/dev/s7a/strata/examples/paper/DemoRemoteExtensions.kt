package dev.s7a.strata.examples.paper

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteRegistry

/**
 * Independent example component and modifier using public projection and client-factory contracts only.
 * Consumers install [registerClient] before client negotiation and register [types] under their Paper plugin owner.
 * These application-specific primitives are not Strata built-ins.
 */
public object DemoRemoteExtensions {
    private val markerType = ProjectionType(ResourceId("strata_example", "marker"))
    private val activationType = ProjectionType(ResourceId("strata_example", "activation"))

    public val types: Set<ProjectionType> = setOf(markerType, activationType)

    /**
     * Creates a portable custom paint primitive with an optional ordinary modifier chain.
     */
    public fun marker(
        color: ArgbColor,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    ): Element = Marker(color, modifier, key)

    /**
     * Creates a custom active modifier whose local press policy is installed separately on each client.
     */
    public fun activation(callback: () -> Unit): Modifier = Modifier.Empty.then(Activation(callback))

    /**
     * Installs trusted decoders and factories; this function has no dependency on Paper classes.
     */
    public fun registerClient(registry: RemoteRegistry) {
        registry.element(markerType, { value ->
            val integer = requireNotNull(value as? ProjectionValue.Integer).value
            require(integer in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            ArgbColor(integer.toInt())
        }) { color, context ->
            require(context.children.isEmpty())
            marker(color, context.modifier, context.key)
        }
        registry.modifier(activationType, { value ->
            requireNotNull(value as? ProjectionValue.Integer).value.also { require(0 < it) }
        }) { endpoint, actions ->
            Modifier.Empty.onPress { actions.send(endpoint, activationType, ProjectionValue.Absent) }
        }
    }

    /**
     * Immutable example paint description, usable locally without a remote registry.
     */
    private class Marker(
        val color: ArgbColor,
        modifier: Modifier,
        key: ElementKey<*>?,
    ) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, markerNodeType, modifier = modifier) {
        override val projection: DeclarationProjection<*> = DeclarationProjection(markerType, color) { value, _ -> ProjectionValue.Integer(value.value.toLong()) }
    }

    /**
     * Client/local retained implementation of the example primitive.
     */
    private class MarkerNode(
        var color: ArgbColor,
    ) : Node(),
        MeasureNode,
        PaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(20, 20))

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }
    }

    /**
     * Server callback projection retaining the handler locally and exporting an empty-payload endpoint.
     */
    private class Activation(
        val callback: () -> Unit,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = activationNodeType
        override val projection: DeclarationProjection<*> =
            DeclarationProjection(activationType, callback) { action, scope ->
                ProjectionValue.Integer(scope.action(ProjectionAction(activationType, { value -> require(value === ProjectionValue.Absent) }) { action() }))
            }
    }

    /**
     * Ordinary local behavior keeps this extension usable without remote registration.
     */
    private class ActivationNode(
        var callback: () -> Unit,
    ) : ModifierNode(),
        PointerInputNode,
        LifecycleNode {
        override fun attach() = Unit

        override fun detach() = Unit

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            if (event is PointerEvent.Press && event.button == PointerButton.Primary) {
                callback()
                return InputResult.Consumed
            }
            return InputResult.Ignored
        }

        override fun dispose() {
            callback = {}
        }
    }

    private val markerNodeType =
        ElementType(Marker::class, MarkerNode::class, {}, { MarkerNode(it.color) }, { _, current, node ->
            node.color = current.color
            DirtyMask.of(DirtyPhase.Paint)
        })
    private val activationNodeType =
        ModifierNodeType(Activation::class, ActivationNode::class, {}, { ActivationNode(it.callback) }, { _, current, node ->
            node.callback = current.callback
            DirtyMask.None
        })
}
