package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.runtime.platform.identitySet
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Independent frozen pre-change validator from master 414cb972ffa8c89a4aee2102d1dc2834dbe32eae.
 * Keep the eager sibling sets: the controls compare exact keyed callbacks and first failure with production.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class OriginalDescriptionValidator {
    /**
     * Validates the complete proposed root with the original eager algorithm.
     */
    fun validate(root: Element) {
        val active = identitySet<Element>()
        visit(root, active)
    }

    /**
     * Validates a fresh dynamic list with the original eager algorithm.
     */
    fun validateChildren(children: List<Element>) {
        val active = identitySet<Element>()
        val keys = HashSet<ElementKey<*>>()
        children.forEach { child ->
            val identity = child.identity
            if (identity is ElementIdentity.Keyed) {
                require(keys.add(identity.key)) { "Duplicate direct-sibling key: ${identity.key}." }
            }
            visit(child, active)
        }
    }

    private fun visit(
        element: Element,
        active: MutableSet<Element>,
    ) {
        require(active.add(element)) { "An element description contains a cycle." }
        element.type.validateErased(element)
        element.modifier.elements().forEach { modifier -> modifier.type.validateErased(modifier) }
        val keys = HashSet<ElementKey<*>>()
        element.children.forEach { child ->
            val identity = child.identity
            if (identity is ElementIdentity.Keyed) {
                require(keys.add(identity.key)) { "Duplicate direct-sibling key: ${identity.key}." }
            }
            visit(child, active)
        }
        active.remove(element)
    }
}
