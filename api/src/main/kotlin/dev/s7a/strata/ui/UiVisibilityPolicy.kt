package dev.s7a.strata.ui

/**
 * Immutable HUD rules, resolved by category, then screen kind, then [default].
 */
public class UiVisibilityPolicy(
    public val default: UiVisibility = UiVisibility.Hide,
    categories: Map<UiCategory, UiVisibility> = emptyMap(),
    screens: Map<UiScreenKind, UiVisibility> = emptyMap(),
) {
    private val categoryRules = categories.toMap()
    private val screenRules = screens.toMap()

    /**
     * A detached snapshot of the category overrides.
     */
    public val categories: Map<UiCategory, UiVisibility> get() = categoryRules.toMap()

    /**
     * A detached snapshot of the screen classification overrides.
     */
    public val screens: Map<UiScreenKind, UiVisibility> get() = screenRules.toMap()

    override fun equals(other: Any?): Boolean = other is UiVisibilityPolicy && default == other.default && categoryRules == other.categoryRules && screenRules == other.screenRules

    override fun hashCode(): Int = 31 * (31 * default.hashCode() + categoryRules.hashCode()) + screenRules.hashCode()

    /**
     * Resolves an ordinary screen; absence of a screen always keeps the HUD visible.
     */
    public fun resolve(
        kind: UiScreenKind?,
        category: UiCategory? = null,
    ): UiVisibility = if (kind == null) UiVisibility.KeepVisible else categoryRules[category] ?: screenRules[kind] ?: default
}
