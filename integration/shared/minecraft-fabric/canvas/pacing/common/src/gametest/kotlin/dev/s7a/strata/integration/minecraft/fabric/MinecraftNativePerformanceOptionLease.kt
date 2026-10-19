package dev.s7a.strata.integration.minecraft.fabric

/**
 * Captures borrowed fixture options without mutation, then applies them inside the caller's protected lifetime.
 * Checks owner access before capture, apply and close, including repeated close.
 * After application starts, restores and reads back every snapshot once, including setters that mutate and fail.
 * No game API or runtime artifact owns this test-only helper.
 */
internal class MinecraftNativePerformanceOptionLease(
    private val checkOwner: () -> Unit,
) : AutoCloseable {
    private var state = State.Capturing
    private val applications = mutableListOf<() -> Unit>()
    private val restorations = mutableListOf<() -> Unit>()

    init {
        checkOwner()
    }

    /**
     * Captures the original value and bounded apply/restore actions before application starts.
     */
    fun <T> capture(
        read: () -> T,
        write: (T) -> Unit,
        borrowed: T,
    ) {
        checkOwner()
        check(state == State.Capturing) { "Fixture options must be captured before application." }
        val previous = read()
        applications.add { write(borrowed) }
        restorations.add {
            write(previous)
            check(read() == previous) { "A borrowed native fixture option was not restored." }
        }
    }

    /**
     * Applies once; the caller must close after either successful or failing application.
     */
    fun apply() {
        checkOwner()
        check(state == State.Capturing) { "Fixture options can only be applied once." }
        state = State.Applied
        try {
            applications.forEach { it() }
        } finally {
            applications.clear()
        }
    }

    override fun close() {
        checkOwner()
        if (state == State.Closed) return
        val applied = state == State.Applied
        state = State.Closed
        val failure =
            try {
                if (applied) restoreAll() else null
            } finally {
                applications.clear()
                restorations.clear()
            }
        failure?.let { throw it }
    }

    private fun restoreAll(): Throwable? {
        var failure: Throwable? = null
        restorations.forEach { restore ->
            val caught = runCatching(restore).exceptionOrNull()
            val first = failure
            if (first == null) {
                failure = caught
            } else if (caught != null && first !== caught) {
                first.addSuppressed(caught)
            }
        }
        return failure
    }

    /**
     * Monotonic owner-thread transaction state.
     */
    private enum class State {
        Capturing,
        Applied,
        Closed,
    }
}
