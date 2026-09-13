package dev.s7a.strata.modifier

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.reflect.KClass

/**
 * Stable referential token and typed bridge for one modifier description and node pair.
 *
 * An application retains one instance as a singleton for each logical modifier kind.
 * Two separately constructed tokens remain incompatible even when their class tokens and hooks are otherwise equal.
 * The complete incoming tree is validated before the runtime invokes creation or update hooks.
 *
 * @param E the immutable modifier description type.
 * @param N the retained modifier node type.
 * @param elementClass the runtime class used to validate and cast descriptions at the erased boundary.
 * @param nodeClass the runtime class used to validate and cast retained nodes at the erased boundary.
 * @param validateLocal checks properties owned by one description before retained mutation.
 * @param createNode creates a fresh, never-owned modifier node for a validated description.
 * @param updateNode applies previous and current descriptions on the owner thread and reports affected phases.
 */
public class ModifierNodeType<E : ModifierElement, N : ModifierNode> public constructor(
    private val elementClass: KClass<E>,
    private val nodeClass: KClass<out N>,
    private val validateLocal: (E) -> Unit,
    private val createNode: (E) -> N,
    private val updateNode: (E, E, N) -> DirtyMask,
) {
    @Suppress("UNCHECKED_CAST")
    private fun checkedElement(element: ModifierElement): E {
        require(element.type === this) { "The modifier uses another node type." }
        require(elementClass.isInstance(element)) { "Modifier node type rejected the description." }
        return element as E
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkedNode(node: ModifierNode): N {
        require(nodeClass.isInstance(node)) { "Modifier node type rejected the node." }
        return node as N
    }

    /**
     * Validates one modifier description through its typed local hook.
     *
     * The runtime invokes this function while validating the complete incoming tree and before retained mutation.
     *
     * @param element the description to validate.
     * @throws IllegalArgumentException when the token or description type is wrong.
     * @throws Throwable when the local validation hook fails.
     */
    @InternalStrataRuntimeApi
    public fun validateErased(element: ModifierElement) {
        validateLocal(checkedElement(element))
    }

    /**
     * Creates one detached modifier node through the typed creation hook.
     *
     * The creation hook must return a fresh node that has never belonged to a runtime.
     * The runtime claims the node immediately after this function returns.
     * Node construction initializes ordinary data only; lifecycle attachment owns external resources.
     *
     * @param element the description to create.
     * @return a fresh detached node of the declared node class.
     * @throws IllegalArgumentException when the token or returned node type is wrong.
     * @throws Throwable when node creation fails.
     */
    @InternalStrataRuntimeApi
    public fun createErased(element: ModifierElement): ModifierNode = checkedNode(createNode(checkedElement(element)))

    /**
     * Updates one retained modifier node through its typed diff hook.
     *
     * The runtime invokes this function on the owning tree thread.
     * The returned mask contains only phases affected by the property change.
     * A failure propagates without a last-good fallback and poisons the owning tree.
     *
     * @param previous the retained description.
     * @param current the incoming description.
     * @param node the retained node to update.
     * @return the phases affected by the update.
     * @throws IllegalArgumentException when a description or node violates this token.
     * @throws Throwable when the update hook fails.
     */
    @InternalStrataRuntimeApi
    public fun updateErased(
        previous: ModifierElement,
        current: ModifierElement,
        node: ModifierNode,
    ): DirtyMask = updateNode(checkedElement(previous), checkedElement(current), checkedNode(node))
}
