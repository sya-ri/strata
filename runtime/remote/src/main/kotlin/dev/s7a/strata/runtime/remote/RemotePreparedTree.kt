package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier

/**
 * One current-state-bounded decoded presentation tree owned by a client session.
 * Replacement or close releases old decoded properties and factory captures through the retained host's normal lifecycle.
 * Decoded factories are reused only for the same retained identity, exact schema, and immutable wire properties.
 * This derived cache contains no server state and is bounded by the current tree; replacing properties or removing identities retires entries.
 */
internal class RemotePreparedTree(
    val tree: RemoteTree,
    val decoded: Map<Long, Node>,
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
            val modifier =
                factories.modifiers.zip(node.modifiers).fold(Modifier.Empty) { chain, (factory, declaration) ->
                    chain.then(factory.create(RemoteModifierContext(declaration.identity, actions, states)))
                }
            val context = RemoteElementContext(identity, modifier, node.children.map(::visit), actions, states)
            return factories.component.create(context).also { budget.checkTime() }
        }
        return visit(tree.root)
    }

    /**
     * Updates native editing values outside declarative evaluation and prunes retired source identities.
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
                    if (node.component.phase == phase) node.component.prepare(RemotePreparationContext(identity, states))
                    node.modifiers.zip(tree.nodes.getValue(identity).modifiers).forEach { (factory, declaration) ->
                        budget.visit()
                        if (factory.phase == phase) factory.prepare(RemotePreparationContext(declaration.identity, states))
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
     */
    class Component(
        val phase: RemotePreparationPhase,
        val prepare: (RemotePreparationContext) -> Unit,
        val create: (RemoteElementContext) -> Element,
    )

    /**
     * Prepared active behavior using the same bounded state store as component factories.
     */
    class ActiveModifier(
        val phase: RemotePreparationPhase,
        val prepare: (RemotePreparationContext) -> Unit,
        val create: (RemoteModifierContext) -> Modifier,
    )
}
