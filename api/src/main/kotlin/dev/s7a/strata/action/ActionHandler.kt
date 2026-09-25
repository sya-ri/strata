package dev.s7a.strata.action

import dev.s7a.strata.ui.UiSession

/**
 * Internal type-erased action callback captured from one Modifier entry.
 *
 * @property key exact referential key accepted by this callback.
 * @param callback synchronous callback that receives values only after key matching.
 */
internal class ActionHandler(
    val key: ActionKey<*>,
    private val callback: UiSession.(Any) -> ActionResult,
) {
    /**
     * Delivers one already type-checked value and returns its propagation result.
     *
     * @param value action payload whose key matched [key].
     * @return callback propagation result.
     */
    fun dispatch(
        session: UiSession,
        value: Any,
    ): ActionResult = callback(session, value)
}
