package dev.s7a.strata.action

/**
 * Referentially identified key for one typed component action.
 *
 * A component and its matching modifier extension share one key instance.
 * Keys intentionally use object identity so unrelated libraries may use equal display names without intercepting each other's actions.
 * The key owns no callbacks or runtime resources and is safe to retain for the lifetime of an application.
 *
 * @param T immutable action payload type.
 * @property name diagnostic English name used only in failures and debugging output.
 */
public class ActionKey<T : Any>(
    public val name: String,
) {
    init {
        require(name.isNotBlank()) { "An action key name must not be blank." }
    }

    override fun toString(): String = "ActionKey($name)"
}
