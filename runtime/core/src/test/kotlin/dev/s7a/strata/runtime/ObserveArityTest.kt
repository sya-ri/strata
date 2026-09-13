package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Compiles and executes the largest generated overload, including its extension receiver and nullable mixed value types.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObserveArityTest {
    @Test
    fun twentyTwoSourcesKeepArgumentOrderAndNullableValueTypes() {
        val source = ObserveTestSource(2)
        val last = ObserveTestSource<String?>(null)
        var sum = 0
        var text: String? = "uninitialized"
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                Observe(
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    source,
                    last,
                ) { a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v ->
                    sum = listOf(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u).sum()
                    text = v
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            assertEquals(42, sum)
            assertEquals(null, text)
            last.publish("typed")
            session.frame(Constraints())
            assertEquals("typed", text)
            assertEquals(1, source.subscriptions)
            assertEquals(1, last.subscriptions)
        }
    }
}
