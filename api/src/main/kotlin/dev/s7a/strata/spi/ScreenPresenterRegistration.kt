package dev.s7a.strata.spi

/**
 * Ownership handle for one installed [ScreenPresenter].
 *
 * Close is thread-safe and idempotent.
 * It removes only the exact registration represented by this handle and never removes a later presenter.
 * Runtime bootstrap code owns the handle for the complete platform-runtime lifetime.
 */
@InternalStrataRuntimeApi
public interface ScreenPresenterRegistration : AutoCloseable {
    /**
     * Removes this registration when it remains current.
     *
     * Concurrent presentation that already captured the presenter may finish normally.
     */
    override fun close()
}
