@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

/**
 * Verifies terminal ownership release for session content.
 */
internal class SessionContentTest {
    @Test
    fun detachedSessionRetainsContentUntilCloseReleasesIt() {
        val probe = TestProbe()
        var evaluations = 0
        val content: () -> Element =
            {
                evaluations += 1
                probe.root(emptyList())
            }
        val session = UiSession(RejectingDispatcher, content = content)

        session.attach()
        session.detach()
        assertSame(content, session.retainedContent())
        assertEquals(1, evaluations)

        session.close()
        assertNull(session.retainedContent())
        session.close()
    }

    @Test
    fun closeReleasesContentBeforeLifecycleCleanupCallbacks() {
        val probe = TestProbe()
        lateinit var session: UiSession
        val content: () -> Element =
            {
                probe.element(
                    tag = TestProbe.ProbeId("root"),
                    onDetach = {
                        assertNull(session.retainedContent())
                    },
                )
            }
        session = UiSession(RejectingDispatcher, content = content)
        session.attach()

        session.close()
        assertNull(session.retainedContent())
    }

    @Test
    fun terminalFailureReleasesContentAndPreservesItsIdentity() {
        val primary = IllegalArgumentException("content")
        val content: () -> Element = { throw primary }
        val session = UiSession(RejectingDispatcher, content = content)

        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { session.attach() })
        assertNull(session.retainedContent())
        session.close()
    }

    @Test
    fun pipelineFailureReleasesContentBeforeLifecycleCleanupCallbacks() {
        val primary = IllegalStateException("paint")
        val probe =
            TestProbe(
                failingPaintTag = TestProbe.ProbeId("root"),
                paintFailure = primary,
            )
        lateinit var session: UiSession
        val content: () -> Element =
            {
                probe.element(
                    tag = TestProbe.ProbeId("root"),
                    onDetach = {
                        assertNull(session.retainedContent())
                    },
                )
            }
        session = UiSession(RejectingDispatcher, content = content)
        session.attach()

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                session.frame(Constraints.fixed(2, 1))
            }
        assertSame(primary, thrown)
        session.close()
    }

    private fun UiSession.retainedContent(): Any? =
        UiSession::class.java
            .getDeclaredField("retainedContent")
            .apply { isAccessible = true }
            .get(this)

    private object RejectingDispatcher : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = throw IllegalStateException("Session content tests do not dispatch coroutine work.")
    }
}
