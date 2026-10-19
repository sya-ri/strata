package dev.s7a.strata.runtime.minecraft.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies subscription release identity, failure cutoff and thread ownership without native client state.
 */
internal class FabricMinecraftOwnerSubscriptionTest {
    @Test
    fun repeatedOldReleaseCannotRemoveANewRegistrationOfTheSameCallback() {
        val callback: () -> Unit = {}
        var observer: (() -> Unit)? = callback
        val previous = minecraftOwnerSubscription(Thread.currentThread()) { if (observer === callback) observer = null }
        previous.close()
        assertNull(observer)
        observer = callback
        previous.close()
        assertSame(callback, observer)
    }

    @Test
    fun failedReleaseIsNeverRetried() {
        val failure = IllegalStateException("release")
        var attempts = 0
        val subscription =
            minecraftOwnerSubscription(Thread.currentThread()) {
                attempts += 1
                throw failure
            }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { subscription.close() })
        subscription.close()
        assertEquals(1, attempts)
    }

    @Test
    fun reentrantReleaseCannotInvokeTheCallbackAgain() {
        var attempts = 0
        lateinit var subscription: AutoCloseable
        subscription =
            minecraftOwnerSubscription(Thread.currentThread()) {
                attempts += 1
                if (attempts == 1) subscription.close()
            }
        subscription.close()
        assertEquals(1, attempts)
    }

    @Test
    fun wrongThreadCloseRejectsWithoutConsumingTheOwnerRelease() {
        var attempts = 0
        val subscription = minecraftOwnerSubscription(Thread.currentThread()) { attempts += 1 }
        for (closed in listOf(false, true)) {
            if (closed) subscription.close()
            val failure = AtomicReference<Throwable?>()
            val thread = Thread { failure.set(runCatching { subscription.close() }.exceptionOrNull()) }
            thread.start()
            thread.join()
            assertInstanceOf(IllegalStateException::class.java, failure.get())
            assertEquals(if (closed) 1 else 0, attempts)
        }
        subscription.close()
        assertEquals(1, attempts)
    }
}
