package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable deferred callback adapter retaining a profile evaluator without retaining a callback-lifetime scope.
 * Each owner-thread evaluation creates a fresh scope, permits zero or one root, and propagates failures unchanged.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObserveContent(
    private val evaluator: ComponentEvaluator?,
    private val content: UiScope.(List<Any?>) -> Unit,
) {
    /**
     * Constructs the current optional child with a fresh scope and the captured profile.
     */
    fun evaluate(values: List<Any?>): List<Element> {
        val collect: UiScope.() -> Unit = {
            Stack { content(values) }
        }
        val children = (evaluator?.evaluate(collect) ?: buildComponentTree(collect)).children
        require(children.size <= 1) { "Observe content must emit zero or one root; use Row or Column for multiple children." }
        return children
    }
}
