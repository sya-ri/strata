package dev.s7a.strata.semantics

import dev.s7a.strata.text.UiText

/**
 * Unresolved accessibility information for one retained node.
 *
 * @property label an optional unresolved label.
 * @property role an optional typed role.
 * @property value an optional unresolved value description.
 * @property disabled whether the node is disabled.
 * @property selected nullable selected state for selectable roles such as a tab.
 * @property checked nullable checked state for checkbox-like roles.
 */
public data class Semantics(
    public val label: UiText? = null,
    public val role: SemanticsRole? = null,
    public val value: UiText? = null,
    public val disabled: Boolean = false,
    public val selected: Boolean? = null,
    public val checked: Boolean? = null,
)
