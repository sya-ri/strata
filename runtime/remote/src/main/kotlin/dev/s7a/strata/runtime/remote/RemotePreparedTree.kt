package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier

/**
 * One current-state-bounded decoded presentation tree owned by a client session.
 * The client session calls every operation under its construction execution owner.
 * Replacement or close releases old decoded properties and factory captures through the retained host's normal lifecycle.
 * Decoded factories are reused only within the same frozen registry for the same retained identity, exact schema, and immutable wire properties.
 * Node wrappers additionally require the same ordered modifier declarations; child topology remains in the independently validated current tree.
 * This derived cache contains no server state and is bounded by the current tree; replacing properties or removing identities retires entries.
 */
internal class RemotePreparedTree(
    val tree: RemoteTree,
    val decoded: Map<Long, Node>,
    /**
     * Private frozen-registry certification token; a different registry cannot reuse these decoded closures.
     */
    val registryIdentity: Any,
) {
    /**
     * Constructs the complete description while the platform component runtime is active.
     */
    fun build(
        actions: RemoteClientActions,
        states: RemoteClientStates,
        limits: RemoteLimits = RemoteLimits(),
    ): Element {
        val budget = RemoteWorkBudget(limits)

        fun visit(identity: Long): Element {
            budget.visit()
            val node = tree.nodes.getValue(identity)
            val factories = decoded.getValue(identity)
            var modifier: Modifier = Modifier.Empty
            factories.modifiers.forEachIndexed { index, factory ->
                modifier = modifier.then(factory.create(RemoteModifierContext(node.modifiers[index].identity, actions, states)))
            }
            val context = RemoteElementContext(identity, modifier, node.children.map(::visit), actions, states)
            return factories.component.create(context).also { budget.checkTime() }
        }
        return visit(tree.root)
    }

    /**
     * Updates native editing values outside declarative evaluation and prunes retired source identities.
     * Full ordered phase visits and their work/deadline checks remain required, including reused records and pure defaults.
     * Only the registry-owned no-op default omits context creation; caller callbacks always run in their declared phase.
     */
    fun prepare(
        states: RemoteClientStates,
        limits: RemoteLimits = RemoteLimits(),
    ) {
        val budget = RemoteWorkBudget(limits)
        states.update {
            RemotePreparationPhase.entries.forEach { phase ->
                decoded.forEach { (identity, node) ->
                    budget.visit()
                    if (node.component.phase == phase && node.component.hasPreparation) node.component.prepare(RemotePreparationContext(identity, states))
                    val declarations = tree.nodes.getValue(identity).modifiers
                    node.modifiers.forEachIndexed { index, factory ->
                        budget.visit()
                        if (factory.phase == phase && factory.hasPreparation) factory.prepare(RemotePreparationContext(declarations[index].identity, states))
                    }
                }
            }
        }
        budget.checkTime()
    }

    /**
     * Type-checked property captures retaining only trusted registered factories and detached data.
     */
    class Node(
        val component: Component,
        val modifiers: List<ActiveModifier>,
    )

    /**
     * Typed preparation and construction closures sharing one validated property record.
     * A false preparation flag is reserved for the registry-owned default; every caller callback remains observable.
     */
    class Component(
        val phase: RemotePreparationPhase,
        val prepare: (RemotePreparationContext) -> Unit,
        val create: (RemoteElementContext) -> Element,
        val hasPreparation: Boolean,
    )

    /**
     * Prepared active behavior using the same bounded state store as component factories.
     * Only the registry-owned default can omit a preparation context; retained state is still prepared every cutoff.
     */
    class ActiveModifier(
        val phase: RemotePreparationPhase,
        val prepare: (RemotePreparationContext) -> Unit,
        val create: (RemoteModifierContext) -> Modifier,
        val hasPreparation: Boolean,
    )
}
