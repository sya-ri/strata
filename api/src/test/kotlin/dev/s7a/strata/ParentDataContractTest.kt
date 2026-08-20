package dev.s7a.strata

import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the typed parent-data key and erased runtime bridge contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ParentDataContractTest {
    @Test
    fun keysWithEqualTypeTokensRemainReferentiallyDistinct() {
        val first = ParentDataKey(String::class)
        val second = ParentDataKey(String::class)

        assertNotSame(first, second)
    }

    @Test
    fun erasedBridgeReturnsTheExactValueInstance() {
        val key = ParentDataKey(String::class)
        val value = String(charArrayOf('v', 'a', 'l', 'u', 'e'))

        assertSame(value, key.castErased(value))
    }

    @Test
    fun erasedBridgeAcceptsBoxedValuesForKotlinPrimitiveKeys() {
        val key = ParentDataKey(Int::class)

        assertEquals(7, key.castErased(7))
    }

    @Test
    fun erasedBridgeRejectsWrongRuntimeClass() {
        val key = ParentDataKey(String::class)

        assertThrows(IllegalArgumentException::class.java) { key.castErased(Any()) }
    }

    @Test
    fun providerFailureKeepsItsExactThrowableIdentity() {
        val key = ParentDataKey(String::class)
        val failure = IllegalStateException("provider failed")
        val provider = ThrowingProvider(key, failure)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                key.castErased(provider.parentData())
            }

        assertSame(failure, thrown)
    }

    private class ThrowingProvider(
        override val parentDataKey: ParentDataKey<String>,
        private val failure: Throwable,
    ) : ModifierNode(),
        ParentDataModifierNode<String> {
        override fun parentData(): String = throw failure
    }
}
