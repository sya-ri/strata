package dev.s7a.strata.modifier

import dev.s7a.strata.action.ActionDispatcher
import dev.s7a.strata.action.ActionHandler
import dev.s7a.strata.action.ActionKey
import dev.s7a.strata.action.ActionResult
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Handles one typed component action emitted by the component wrapped by this modifier.
 *
 * @param key referential action key shared with the emitting component.
 * @param callback synchronous owner-thread handler that decides whether delivery continues.
 * @return this chain with one appended active action handler.
 * @throws Throwable when [callback] fails during component action delivery.
 */
public fun <T : Any> Modifier.onAction(
    key: ActionKey<T>,
    callback: (T) -> ActionResult,
): Modifier =
    then(
        ActionModifier.Element(
            ActionHandler(key) { value ->
                @Suppress("UNCHECKED_CAST")
                callback(value as T)
            },
        ),
    )

/**
 * Captures the typed action handlers declared directly on this modifier chain.
 *
 * Custom element descriptions call this during construction and retain the returned immutable dispatcher in their retained node.
 * Callback-only description changes replace the dispatcher without requiring measure, layout, paint, or semantics work.
 *
 * @return immutable innermost-first action delivery snapshot, or an empty dispatcher when no actions are present.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.actionDispatcher(): ActionDispatcher {
    val handlers =
        elements().mapNotNull { element ->
            (element as? ActionModifier.Element)?.handler
        }
    return if (handlers.isEmpty()) ActionDispatcher.Empty else ActionDispatcher(handlers)
}
