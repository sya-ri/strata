@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.runtime.spi.RuntimeUiInput
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.ui.UiGameAction
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Presentation and input controls follow serialized execution ownership without granting access to another owner.
 */
internal class UiExecutionOwnerTest {
    @Test
    fun controlsAndInputFollowTheSameOwnerAcrossPhysicalThreads() {
        val owner = RuntimeExecutionOwner()
        lateinit var session: RuntimeUiController
        owner.run {
            session = RuntimeUiController(UiPresentation.Hud, apply = { session.applied(it.sequence) }, close = {})
            session.start()
        }
        val pressed = mutableSetOf<UiGameAction>()
        val input = owner.run { RuntimeUiInput(UiGameAction.entries.associateWith { it }, { pressed.add(it) }, { pressed.remove(it) }) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertThrows(IllegalStateException::class.java) { session.presentation }
            assertThrows(IllegalStateException::class.java) { input.configure(session, UiInputPolicy.All, false) }
            executor
                .submit {
                    owner.run {
                        assertEquals(UiPresentation.Hud, session.presentation)
                        session.switch(UiPresentation.Screen)
                        input.configure(session, UiInputPolicy.Movement, false)
                        input.event(listOf(UiGameAction.Movement), true, false)
                        assertEquals(setOf(UiGameAction.Movement), pressed)
                        RuntimeExecutionOwner().run {
                            assertThrows(IllegalStateException::class.java) { session.close() }
                            assertThrows(IllegalStateException::class.java) { input.reset() }
                        }
                    }
                }.get(5, TimeUnit.SECONDS)
            owner.run {
                assertEquals(UiPresentation.Screen, session.presentation)
                input.close()
                session.close()
                session.close()
                assertTrue(pressed.isEmpty())
                assertTrue(session.status is UiSessionStatus.Closed)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            owner.run {
                input.close()
                session.close()
            }
        }
    }
}
