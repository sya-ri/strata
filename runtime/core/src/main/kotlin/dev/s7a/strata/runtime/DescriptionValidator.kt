package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Validates a complete element description before retained state is mutated.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class DescriptionValidator {
    /**
     * Validates local properties, direct-sibling keys, and cycles throughout [root].
     *
     * @param root the proposed root description.
     */
    fun validate(root: Element) {
        val active = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
        visit(root, active)
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
