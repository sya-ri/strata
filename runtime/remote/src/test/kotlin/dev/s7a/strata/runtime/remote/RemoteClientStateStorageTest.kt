package dev.s7a.strata.runtime.remote

import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Repeats every shared callback boundary while requiring the candidate's derived editable index to release its entries.
 */
internal class RemoteClientStateStorageTest : RemoteClientStateReentryTest() {
    override fun assertStorageReleased(states: RemoteClientStates) {
        super.assertStorageReleased(states)
        val field = RemoteClientStates::class.java.getDeclaredField("editable")
        field.isAccessible = true
        val members = field.get(states)
        val empty =
            when (members) {
                is Map<*, *> -> members.isEmpty()
                is Collection<*> -> members.isEmpty()
                else -> error("Unsupported membership collection")
            }
        assertTrue(empty)
    }
}
