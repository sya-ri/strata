package dev.s7a.strata.runtime.minecraft.font

import org.junit.jupiter.api.Assertions.assertTrue
import java.lang.ref.WeakReference

/**
 * Bounded reachability assertions for callback and snapshot ownership tests after their creating stack frame returns.
 */
internal object FontTestReferences {
    /**
     * Requests collection without allocating pressure buffers and waits briefly for all weak references to clear.
     *
     * @param references weak observations whose original owners have left their creating scope.
     * @throws AssertionError when an observed owner remains reachable after the bounded collection attempts.
     */
    fun assertCollected(vararg references: WeakReference<*>) {
        repeat(20) {
            if (references.all { reference -> reference.get() == null }) return
            System.gc()
            Thread.sleep(10)
        }
        assertTrue(references.all { reference -> reference.get() == null }, "Font loading retained a callback-lifetime owner.")
    }
}
