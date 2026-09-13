package dev.s7a.strata.component

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource

/**
 * Emits one immutable observed region without subscribing or evaluating its deferred content.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun UiScope.emitObserve(
    sources: List<StateSource<*>>,
    modifier: Modifier,
    key: ElementKey<*>?,
    content: UiScope.(List<Any?>) -> Unit,
) {
    checkUsable()
    element(ObserveElement(sources.toList(), ObserveContent(ComponentRuntimeBridge.currentOrNull()?.retainEvaluator(), content), modifier, key))
}

/**
 * Emits a single source-backed component, preserving its complete modifier chain on the actual child.
 * The wrapper owns only the public sibling key and observation; parent data delegates to that child.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun UiScope.emitObservedComponent(
    sources: List<StateSource<*>>,
    key: ElementKey<*>?,
    content: UiScope.(List<Any?>) -> Unit,
) {
    checkUsable()
    element(
        ObserveElement(
            sources.toList(),
            ObserveContent(ComponentRuntimeBridge.currentOrNull()?.retainEvaluator(), content, requireSingleRoot = true),
            Modifier.Empty,
            key,
            transparent = true,
        ),
    )
}
