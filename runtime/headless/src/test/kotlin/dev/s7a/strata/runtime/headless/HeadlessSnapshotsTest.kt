package dev.s7a.strata.runtime.headless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.ArrayList

/**
 * Verifies defensive semantics snapshot ownership and mutation behavior.
 */
internal class HeadlessSnapshotsTest {
    @Test
    fun snapshotDetachesSourceAndRejectsOutputMutation() {
        val source = ArrayList(listOf("first", "second"))
        val snapshot = immutableSnapshot(source)

        source[0] = "changed"
        source += "third"

        assertEquals(listOf("first", "second"), snapshot)
        assertThrows<UnsupportedOperationException> {
            (snapshot as MutableList<String>).add("fourth")
        }
    }
}
