package dev.s7a.strata.element

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.reflect.KClass

/**
 * Stable referential token and typed SPI for one element kind.
 *
 * An application must retain one instance as a singleton for each logical kind.
 *
 * Two separately constructed tokens remain incompatible even when their class tokens and hooks are otherwise equal.
 *
 * @param E the immutable element description class owned by this token.
 * @param N the retained node class created by this token.
 * @param validateLocal checks properties owned by one description; descendants are validated by the engine.
 * @param createNode creates a detached node for a validated description.
 * @param updateNode applies a previous and current description to a retained node and reports affected phases.
 */
public class ElementType<E : Element, N : Node> public constructor(
    private val elementClass: KClass<E>,
    private val nodeClass: KClass<out N>,
    private val validateLocal: (E) -> Unit,
    private val createNode: (E) -> N,
    private val updateNode: (E, E, N) -> DirtyMask,
) {
    private fun <R> bridge(
        previous: Element,
        current: Element,
        node: Node?,
        operation: (E, E, N?) -> R,
    ): R {
        require(previous.type === this) { "The previous element is owned by another element type." }
        require(current.type === this) { "The current element is owned by another element type." }
        require(elementClass.isInstance(previous)) {
            "Element type ${elementClass.qualifiedName} rejected ${previous::class.qualifiedName}."
        }
        require(elementClass.isInstance(current)) {
            "Element type ${elementClass.qualifiedName} rejected ${current::class.qualifiedName}."
        }
        val typedNode =
            if (node == null) {
                null
            } else {
                require(nodeClass.isInstance(node)) {
                    "Element type ${elementClass.qualifiedName} rejected node ${node::class.qualifiedName}."
                }
                nodeClass.java.cast(node)
            }
        return operation(elementClass.java.cast(previous), elementClass.java.cast(current), typedNode)
    }

    /**
     * Validates one description through this token's typed local-property hook.
     *
     * The runtime invokes this function before mutating retained state and propagates validation failures unchanged.
     *
     * @param element the description to validate.
     * @throws IllegalArgumentException when the description is not owned by this token or has the wrong class.
     * @throws Throwable when the local validation hook fails.
     */
    @InternalStrataRuntimeApi
    public fun validateErased(element: Element) {
        bridge(element, element, null) { typedElement, _, _ -> validateLocal(typedElement) }
    }

    /**
     * Creates a node through this token's typed creation hook and verifies the returned class.
     *
     * The returned node is detached and becomes runtime-owned only after successful creation and validation.
     *
     * @param element the description to create from.
     * @return a detached node of the declared node class.
     * @throws IllegalArgumentException when the description is not owned by this token or has the wrong class.
     * It is also thrown when the created node has the wrong class.
     * @throws Throwable when the node creation hook fails.
     */
    @InternalStrataRuntimeApi
    public fun createErased(element: Element): Node =
        bridge(element, element, null) { typedElement, _, _ ->
            val created = createNode(typedElement)
            require(nodeClass.isInstance(created)) {
                "Element type ${elementClass.qualifiedName} created ${created::class.qualifiedName}."
            }
            created
        }

    /**
     * Updates a retained node through this token's typed property-diff hook.
     *
     * The runtime invokes this function on the owning tree thread and propagates failures without a last-good fallback.
     *
     * @param previous the previously retained description.
     * @param current the incoming description.
     * @param node the retained node to update.
     * @return phases affected by the property diff.
     * @throws IllegalArgumentException when a description or node violates this token.
     * @throws Throwable when the update hook fails.
     */
    @InternalStrataRuntimeApi
    public fun updateErased(
        previous: Element,
        current: Element,
        node: Node,
    ): DirtyMask =
        bridge(previous, current, node) { previousElement, currentElement, typedNode ->
            requireNotNull(typedNode) { "A retained node is required for element updates." }
            updateNode(previousElement, currentElement, typedNode)
        }
}
