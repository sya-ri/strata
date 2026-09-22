package dev.s7a.strata.input

/**
 * Immutable keyboard subscription evaluated before local dispatch and again before a remote handler runs.
 * An empty key set or null modifiers accept any key or modifier combination. Modifier matching compares held Shift, Control,
 * Alt, and Super keys exactly; Caps Lock and Num Lock do not change shortcut matching.
 *
 * @param keys physical keys accepted by the subscription; membership is copied.
 * @property modifiers optional exact combination of held modifier keys.
 */
public class KeyboardInputFilter(
    keys: Set<KeyCode> = emptySet(),
    public val modifiers: KeyboardModifiers? = null,
) {
    /**
     * Detached accepted key identities; an empty set accepts any physical key.
     */
    public val keys: Set<KeyCode> = keys.toSet()

    /**
     * Tests only detached event data without invoking application code or consuming input.
     */
    public fun matches(event: KeyboardEvent): Boolean {
        if (keys.isNotEmpty() && (event.key in keys).not()) return false
        val expected = modifiers ?: return true
        val actual = event.modifiers
        return expected.shift == actual.shift && expected.control == actual.control &&
            expected.alt == actual.alt && expected.superKey == actual.superKey
    }

    override fun equals(other: Any?): Boolean = other is KeyboardInputFilter && keys == other.keys && modifiers == other.modifiers

    override fun hashCode(): Int = 31 * keys.hashCode() + (modifiers?.hashCode() ?: 0)
}
