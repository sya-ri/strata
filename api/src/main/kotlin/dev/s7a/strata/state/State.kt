package dev.s7a.strata.state

/**
 * Read-only view of an observable value owned by its construction thread.
 * Reading during screen evaluation records a dependency of that screen.
 * Implementations reject access during a guarded equality comparison or from another thread.
 * The caller owns the value and its lifetime independently of observing screens.
 */
public interface State<out T> {
    /**
     * Returns the current owner-thread value, recording an active screen dependency.
     */
    public val value: T
}
