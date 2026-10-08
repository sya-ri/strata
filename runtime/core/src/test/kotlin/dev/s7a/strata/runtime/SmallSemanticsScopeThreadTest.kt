@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies every callback cardinality checks physical ownership and captured closed collectors release payloads.
 */
internal class SmallSemanticsScopeThreadTest {
    @Test
    fun zeroSingletonAndManyCallbacksRejectOtherThreadsAndReleaseConstructionValues() {
        for (count in listOf(0, 1, 8)) {
            val fixture = SemanticsCollectionFixture()
            val values = MutableList(count) { Semantics(label = UiText.Literal(it.toString())) }
            val node = fixture.node(0, values)
            val rejected = Semantics(label = UiText.Literal("wrong owner"))
            val failures = ArrayList<Throwable>()
            node.emit = { scope ->
                val thread = Thread { failures.add(assertFailsWith<IllegalStateException> { scope.emit(rejected) }) }
                thread.start()
                thread.join()
                values.forEach(scope::emit)
            }
            val session = createRuntimeUiSession { fixture.element(node) }
            try {
                session.attach()
                val frame = session.frame(Constraints.fixed(2, 1))
                val expected = values.toList()
                val scope = assertNotNull(node.scope)
                assertEquals(1, failures.size)
                assertEquals("This runtime object requires its owning execution context.", failures.single().message)
                assertEquals("The callback scope is no longer active.", assertFailsWith<IllegalStateException> { scope.emit(rejected) }.message)
                for (name in listOf("first", "values")) {
                    val field = scope.javaClass.getDeclaredField(name)
                    field.isAccessible = true
                    assertNull(field.get(scope), name)
                }
                values.clear()
                assertEquals(expected, frame.semantics.map { it.semantics })
                val thread = Thread { failures.add(assertFailsWith<IllegalStateException> { scope.emit(rejected) }) }
                thread.start()
                thread.join()
                assertEquals("This runtime object requires its owning execution context.", failures.last().message)
                session.close()
                assertEquals(expected, frame.semantics.map { it.semantics })
            } finally {
                session.close()
            }
        }
    }
}
