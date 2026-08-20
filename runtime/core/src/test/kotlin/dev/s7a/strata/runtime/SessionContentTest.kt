package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
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
        val content =
            SessionContent {
                evaluations += 1
                probe.root(emptyList())
            }
        val session = UiSession(RejectingDispatcher, contentOwner = content)

        session.attach()
        session.detach()
        content.evaluate()
        assertEquals(2, evaluations)

        session.close()
        assertThrows(IllegalStateException::class.java) { content.evaluate() }
        session.close()
    }

    @Test
    fun closeReleasesContentBeforeLifecycleCleanupCallbacks() {
        val probe = TestProbe()
        var contentReference: SessionContent? = null
        val content =
            SessionContent {
                probe.element(
                    tag = TestProbe.ProbeId("root"),
                    onDetach = {
                        assertThrows(IllegalStateException::class.java) {
                            checkNotNull(contentReference).evaluate()
                        }
                    },
                )
            }
        contentReference = content
        val session = UiSession(RejectingDispatcher, contentOwner = content)
        session.attach()

        session.close()
        assertThrows(IllegalStateException::class.java) { content.evaluate() }
    }

    @Test
    fun terminalFailureReleasesContentAndPreservesItsIdentity() {
        val primary = IllegalArgumentException("content")
        val content = SessionContent { throw primary }
        val session = UiSession(RejectingDispatcher, contentOwner = content)

        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { session.attach() })
        val released = assertThrows(IllegalStateException::class.java) { content.evaluate() }
        assertEquals("Session content has already been released.", released.message)
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
        var contentReference: SessionContent? = null
        val content =
            SessionContent {
                probe.element(
                    tag = TestProbe.ProbeId("root"),
                    onDetach = {
                        assertThrows(IllegalStateException::class.java) {
                            checkNotNull(contentReference).evaluate()
                        }
                    },
                )
            }
        contentReference = content
        val session = UiSession(RejectingDispatcher, contentOwner = content)
        session.attach()

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                session.frame(Constraints.fixed(2, 1))
            }
        assertSame(primary, thrown)
        session.close()
    }

    private object RejectingDispatcher : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = throw IllegalStateException("Session content tests do not dispatch coroutine work.")
    }
}
