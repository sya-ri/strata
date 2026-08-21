package dev.s7a.strata.input

/**
 * Typed transition of a pointer across one laid-out hit region.
 */
public enum class PointerHoverEvent {
    /**
     * The pointer moved from outside the hit region to inside it.
     */
    Enter,

    /**
     * The pointer moved from inside the hit region to outside it, or the owning session detached.
     */
    Exit,
}
