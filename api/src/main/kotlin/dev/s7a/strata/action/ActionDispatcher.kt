package dev.s7a.strata.action

/**
 * Immutable callback snapshot extracted from one component's modifier chain.
 *
 * Custom retained components capture a dispatcher from their incoming modifier and invoke [dispatch] only from owner-thread input or lifecycle-safe event callbacks.
 * Delivery visits matching handlers from the innermost modifier toward the outermost modifier and stops at [ActionResult.Consumed].
 * Callback failures propagate unchanged through the active retained-tree operation.
 */
public class ActionDispatcher internal constructor(
    private val handlers: List<ActionHandler>,
) {
    /**
     * Delivers [value] to handlers registered for [key].
     *
     * @param key exact referential action key shared with the handlers.
     * @param value immutable payload owned by the caller for the duration of synchronous delivery.
     * @return consumed when a matching handler accepts the action, otherwise ignored.
     * @throws Throwable when a matching application callback fails.
     */
    public fun <T : Any> dispatch(
        key: ActionKey<T>,
        value: T,
    ): ActionResult {
        handlers.asReversed().forEach { handler ->
            if (handler.key === key && handler.dispatch(value) === ActionResult.Consumed) {
                return ActionResult.Consumed
            }
        }
        return ActionResult.Ignored
    }

    /**
     * Owns the allocation-free empty dispatcher shared by components without handlers.
     */
    internal companion object {
        /**
         * Shared immutable dispatcher used when a component has no action handlers.
         */
        val Empty: ActionDispatcher = ActionDispatcher(emptyList())
    }
}
