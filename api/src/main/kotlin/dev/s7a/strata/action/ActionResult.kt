package dev.s7a.strata.action

/**
 * Result of delivering a typed component action through a modifier chain.
 */
public enum class ActionResult {
    /**
     * The handler accepted the action and stops delivery to earlier handlers.
     */
    Consumed,

    /**
     * The handler declined the action and permits delivery to the next matching handler.
     */
    Ignored,
}
