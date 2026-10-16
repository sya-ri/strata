@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies candidate collector construction fields release payloads after every callback cardinality.
 */
internal class SmallSemanticsCollectorRetentionTest {
    @Test
    fun zeroSingletonAndManyClosedCollectorsReleaseConstructionValues() {
        for (count in listOf(0, 1, 8)) {
            val fixture = SemanticsCollectionFixture()
            val values = MutableList(count) { Semantics(label = UiText.Literal(it.toString())) }
            val node = fixture.node(0, values)
            val session = createRuntimeUiSession { fixture.element(node) }
            try {
                session.attach()
                session.frame(Constraints.fixed(2, 1))
                val scope = assertNotNull(node.scope)
                for (name in listOf("first", "values")) {
                    val field = scope.javaClass.getDeclaredField(name)
                    field.isAccessible = true
                    assertNull(field.get(scope), name)
                }
            } finally {
                session.close()
            }
        }
    }
}
