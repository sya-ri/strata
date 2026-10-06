package dev.s7a.strata

import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierChain
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies empty receivers and immutable chain composition on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ModifierCommonContractTest {
    @Test
    fun companionStartsBuiltInAndCustomExtensionsAndConditionalValues() {
        val empty: Modifier = Modifier
        val enabled = true
        val sized: Modifier = if (enabled) Modifier.size(100, 40) else Modifier
        val disabled: Modifier = if (enabled.not()) Modifier.size(100, 40) else Modifier
        assertSame(Modifier, empty)
        assertSame(empty, disabled)
        assertEquals(Modifier.size(100, 40), sized)
        assertEquals(Modifier.padding(3).size(100, 40), Modifier.panelPadding().size(100, 40))
        assertFailsWith<IllegalArgumentException> { Modifier.size(-1, 40) }
    }

    @Test
    fun emptyCompositionPreservesIdentityAndValueContracts() {
        val chain = Modifier.size(10, 20)
        val anotherEmpty = ModifierChain(emptyList())
        assertSame(Modifier, Modifier.then(Modifier))
        assertSame(chain, Modifier.then(chain))
        assertSame(chain, chain.then(Modifier))
        assertTrue(Modifier.equals(anotherEmpty))
        assertTrue(anotherEmpty.equals(Modifier))
        assertEquals(Modifier.hashCode(), anotherEmpty.hashCode())
        assertEquals("Modifier([])", Modifier.toString())
        assertEquals(Modifier.toString(), anotherEmpty.toString())
        assertFalse(Modifier.equals(chain))
        assertFalse(chain.equals(Modifier))
        assertFalse(Modifier.equals(Unit))
    }

    @Test
    fun ownedSnapshotsAndAppendedChainsRetainOrderedDescriptions() {
        val first = Modifier.size(10, 20).elements().single()
        val second = Modifier.padding(3).elements().single()
        val callerElements = mutableListOf(first)
        val original = ModifierChain(callerElements)
        val snapshot = original.elements()
        callerElements.clear()
        val appended = original.then(second)
        val combined = original.then(Modifier.padding(3))
        assertSame(snapshot, original.elements())
        assertEquals(listOf(first), snapshot)
        assertEquals(listOf(first, second), appended.elements())
        assertEquals(appended, combined)
        assertEquals(appended.hashCode(), combined.hashCode())
        assertEquals(appended.toString(), combined.toString())
    }

    @Test
    @Suppress("DEPRECATION")
    fun deprecatedEmptyRemainsTheSameStarter() {
        assertSame(Modifier, Modifier.Empty)
        assertEquals(Modifier.size(10, 20), Modifier.Empty.size(10, 20))
    }

    private fun Modifier.panelPadding(): Modifier = padding(3)
}
