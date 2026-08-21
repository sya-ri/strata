package dev.s7a.strata.runtime.minecraft

/**
 * Owner-thread mutable value for one Minecraft single-line text field.
 *
 * The state accepts only printable ASCII U+0020 through U+007E, owns its string value, and may be observed by at most one live TextField node.
 * Reads and writes are confined to the thread that creates the state.
 */
public sealed interface MinecraftTextFieldState {
    /**
     * Current printable-ASCII value.
     *
     * A successful write synchronously invalidates an attached TextField that observes this state.
     *
     * @throws IllegalArgumentException when a value contains unsupported text or exceeds [maxLength].
     * @throws IllegalStateException when accessed from another thread.
     */
    public var value: String

    /**
     * Maximum UTF-16 length accepted by [value].
     */
    public val maxLength: Int
}
