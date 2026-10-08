package dev.s7a.strata

import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies composition preserves owned membership and observable values on every platform.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ModifierCompositionTest {
    @Test
    fun extendingAndReusingPrefixesKeepsEveryEarlierValueStable() {
        val descriptions =
            List(32) {
                Modifier.Empty
                    .padding(it)
                    .elements()
                    .single()
            }
        var current = Modifier.Empty
        val prefixes =
            descriptions.map { description ->
                current = current.then(description)
                current
            }
        val hashes = prefixes.map(Modifier::hashCode)
        val texts = prefixes.map(Modifier::toString)
        val extended = current.then(current).then(descriptions.first())

        prefixes.forEachIndexed { index, prefix ->
            assertEquals(descriptions.take(index + 1), prefix.elements())
            assertEquals(hashes[index], prefix.hashCode())
            assertEquals(texts[index], prefix.toString())
            assertEquals(descriptions.take(index + 1) + descriptions, prefix.then(current).elements())
        }
        assertEquals(descriptions + descriptions + descriptions.first(), extended.elements())
        assertEquals(descriptions, current.elements())
        assertSame(current.elements(), current.elements())
    }

    @Test
    fun concatenationRetainsRepeatedDescriptionIdentityAndValueOrder() {
        val first =
            Modifier.Empty
                .padding(1)
                .elements()
                .single()
        val second =
            Modifier.Empty
                .padding(2)
                .elements()
                .single()
        val left = Modifier.Empty.then(first).then(second)
        val right = Modifier.Empty.then(first)
        val combined = left.then(right)
        val equal =
            Modifier.Empty
                .padding(1)
                .padding(2)
                .padding(1)

        assertEquals(listOf(first, second), left.elements())
        assertEquals(listOf(first), right.elements())
        assertEquals(listOf(first, second, first), combined.elements())
        assertSame(first, combined.elements()[0])
        assertSame(first, combined.elements()[2])
        val repeated = left.then(left)
        assertEquals(listOf(first, second, first, second), repeated.elements())
        assertSame(first, repeated.elements()[2])
        assertSame(second, repeated.elements()[3])
        assertEquals(listOf(second, first), combined.elements().subList(1, 3))
        assertEquals(equal, combined)
        assertEquals(equal.hashCode(), combined.hashCode())
        assertEquals(equal.toString(), combined.toString())
    }

    @Test
    fun emptyAndConditionalCompositionPreservesTheExistingIdentity() {
        val empty = Modifier.Empty
        val chain = empty.padding(3).padding(4)
        val conditional = listOf(false, true).map { enabled -> if (enabled) chain.padding(5) else chain }

        assertSame(empty, empty.then(empty))
        assertSame(chain, empty.then(chain))
        assertSame(chain, chain.then(empty))
        assertSame(chain, conditional[0])
        assertEquals(chain.elements() + empty.padding(5).elements(), conditional[1].elements())
        assertEquals(emptyList(), empty.elements())
    }
}
