package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies build rendering releases source ownership before returning HTML or propagating cleanup failure.
 */
internal class WebHtmlTest {
    @Test
    fun buildHtmlEscapesSourceTextAndClosesItsSubscriptionBeforeReturning() {
        var subscriptions = 0
        var releases = 0
        val source =
            StateSource<String> {
                subscriptions += 1
                StateSubscription(StateSnapshot(StateRevision(0), "<script>initial</script>")) { releases += 1 }
            }
        val html = renderWebHtml(ScreenDefinition("Static") { Stack { Text(source) } }, IntSize(320, 100))
        assertTrue(html.contains("&lt;script&gt;initial&lt;/script&gt;"))
        assertEquals(1, subscriptions)
        assertEquals(1, releases)
    }

    @Test
    fun buildDoesNotReturnSuccessfulHtmlWhenSourceCleanupFails() {
        val failure = IllegalStateException("source cleanup")
        val source =
            StateSource<String> {
                StateSubscription(StateSnapshot(StateRevision(0), "Initial")) { throw failure }
            }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                renderWebHtml(ScreenDefinition("Static") { Stack { Text(source) } }, IntSize(320, 100))
            },
        )
    }
}
