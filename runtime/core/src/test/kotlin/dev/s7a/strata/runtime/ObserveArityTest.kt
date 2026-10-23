package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
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
        val sources = (1..21).map { ObserveTestSource(it) }
        val last = ObserveTestSource<String?>(null)
        var values = emptyList<Int>()
        var text: String? = "uninitialized"
        UiSession(TestOwnerDispatcher()) {
            description(sources, last) { current, nullable ->
                values = current
                text = nullable
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            assertEquals((1..21).toList(), values)
            assertEquals(null, text)
            sources[10].publish(1000)
            session.frame(Constraints())
            val updated = (1..21).map { if (it == 11) 1000 else it }
            assertEquals(updated, values)
            assertEquals(null, text)
            last.publish("typed")
            session.frame(Constraints())
            assertEquals(updated, values)
            assertEquals("typed", text)
            sources.forEach { assertEquals(1, it.subscriptions) }
            assertEquals(1, last.subscriptions)
        }
        sources.forEach { assertEquals(1, it.releases) }
        assertEquals(1, last.releases)
    }

    @Test
    fun maximumAritySharesOneSubscriptionForRepeatedSources() {
        val source = ObserveTestSource(2)
        val last = ObserveTestSource<String?>(null)
        var values = emptyList<Int>()
        UiSession(TestOwnerDispatcher()) {
            description(List(21) { source }, last) { current, _ -> values = current }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            assertEquals(List(21) { 2 }, values)
            source.publish(3)
            session.frame(Constraints())
            assertEquals(List(21) { 3 }, values)
            assertEquals(1, source.subscriptions)
            assertEquals(1, last.subscriptions)
        }
        assertEquals(1, source.releases)
        assertEquals(1, last.releases)
    }

    private fun description(
        sources: List<ObserveTestSource<Int>>,
        last: ObserveTestSource<String?>,
        observed: (List<Int>, String?) -> Unit,
    ): Element =
        evaluateComponentTree {
            Observe(
                sources[0],
                sources[1],
                sources[2],
                sources[3],
                sources[4],
                sources[5],
                sources[6],
                sources[7],
                sources[8],
                sources[9],
                sources[10],
                sources[11],
                sources[12],
                sources[13],
                sources[14],
                sources[15],
                sources[16],
                sources[17],
                sources[18],
                sources[19],
                sources[20],
                last,
            ) { a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v ->
                observed(listOf(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u), v)
                Spacer()
            }
        }
}
