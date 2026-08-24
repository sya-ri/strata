package dev.s7a.strata

import dev.s7a.strata.component.VirtualListState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the public owner-thread boundary of virtual-list invalidation state.
 */
internal class VirtualListStateTest {
    @Test
    fun refreshRejectsAnotherThreadBeforeAttachment() {
        val state = VirtualListState<Int>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { failure.set(runCatching { state.refresh() }.exceptionOrNull()) }

        worker.start()
        worker.join()

        assertTrue(failure.get() is IllegalStateException)
    }
}
