package dev.s7a.strata.semantics

import dev.s7a.strata.text.UiText

/**
 * Unresolved accessibility information for one retained node.
 *
 * @property label an optional unresolved label.
 * @property role an optional typed role.
 * @property value an optional unresolved value description.
 * @property disabled whether the node is disabled.
 */
public data class Semantics(
    public val label: UiText? = null,
    public val role: SemanticsRole? = null,
    public val value: UiText? = null,
    public val disabled: Boolean = false,
)
