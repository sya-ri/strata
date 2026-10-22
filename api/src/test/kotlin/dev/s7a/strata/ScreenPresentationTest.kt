@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata

import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.screen.ScreenDefinitionPayload
import dev.s7a.strata.screen.ScreenDefinitionUnavailableException
import dev.s7a.strata.screen.ScreenOpenThreadException
import dev.s7a.strata.screen.ScreenRuntimeUnavailableException
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.ScreenPresenter
import dev.s7a.strata.spi.ScreenPresenters
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies platform presenter registration, ownership transfer, failure, and terminal retention contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ScreenPresentationTest {
    @Test
    fun unavailableRuntimeLeavesDefinitionOwnedByCaller() {
        val definition = ScreenDefinition("unavailable") {}

        assertThrows(ScreenRuntimeUnavailableException::class.java) { definition.open() }

        assertEquals("unavailable", definition.transfer().literalTitle())
    }

    @Test
    fun installedPresenterReceivesExactDefinitionSynchronously() {
        val definition = ScreenDefinition("present") {}
        var received: ScreenDefinition? = null
        ScreenPresenters
            .install(
                ScreenPresenter { candidate ->
                    received = candidate
                    candidate.transfer()
                },
            ).use { registration ->
                definition.open()
            }

        assertSame(definition, received)
        assertThrows(ScreenDefinitionUnavailableException::class.java) { definition.transfer() }
    }

    @Test
    fun duplicateRegistrationFailsWithoutReplacingCurrentPresenter() {
        val calls = mutableListOf<String>()
        ScreenPresenters.install(ScreenPresenter { definition -> calls += definition.transfer().literalTitle() }).use { first ->
            assertThrows(IllegalStateException::class.java) {
                ScreenPresenters.install(ScreenPresenter { calls += "replacement" })
            }
            ScreenDefinition("current") {}.open()
        }

        assertEquals(listOf("current"), calls)
    }

    @Test
    fun registrationCloseIsIdempotentAndReleasesPresenterSlot() {
        val first = ScreenPresenters.install(ScreenPresenter { error("unused") })

        first.close()
        first.close()

        ScreenPresenters.install(ScreenPresenter { definition -> definition.transfer() }).use { second ->
            ScreenDefinition("replacement") {}.open()
        }
    }

    @Test
    fun registrationCloseReleasesPresenterReferenceFromHandle() {
        val registration = ScreenPresenters.install(ScreenPresenter { error("unused") })

        registration.close()

        val entryField = registration.javaClass.getDeclaredField("entry")
        entryField.isAccessible = true
        val entryReference = entryField.get(registration) as AtomicReference<*>
        assertNull(entryReference.get())
    }

    @Test
    fun threadRejectionOccursBeforeTransferAndAllowsRetry() {
        val definition = ScreenDefinition("thread") {}
        ScreenPresenters
            .install(
                ScreenPresenter {
                    throw ScreenOpenThreadException("Test presenter requires its owner thread.")
                },
            ).use { wrongThread ->
                assertThrows(ScreenOpenThreadException::class.java) { definition.open() }
            }

        ScreenPresenters.install(ScreenPresenter { candidate -> candidate.transfer() }).use { ownerThread ->
            definition.open()
        }
    }

    @Test
    fun failureAfterTransferDoesNotReturnOwnershipToCaller() {
        val definition = ScreenDefinition("failed") {}
        val expected = IllegalArgumentException("presentation")
        ScreenPresenters
            .install(
                ScreenPresenter { candidate ->
                    candidate.transfer()
                    throw expected
                },
            ).use { registration ->
                assertSame(expected, assertThrows(IllegalArgumentException::class.java) { definition.open() })
            }

        assertThrows(ScreenDefinitionUnavailableException::class.java) { definition.transfer() }
        assertPayloadReleased(definition)
    }

    @Test
    fun closedDefinitionReportsTypedOwnershipFailure() {
        val definition = ScreenDefinition("closed") {}
        definition.close()

        assertThrows(ScreenDefinitionUnavailableException::class.java) { definition.transfer() }
        assertPayloadReleased(definition)
    }

    private fun assertPayloadReleased(definition: ScreenDefinition) {
        val state = readDefinitionState(definition)
        assertFalse(state.javaClass.declaredFields.any { field -> field.type == ScreenDefinitionPayload::class.java })
    }

    private fun readDefinitionState(definition: ScreenDefinition): Any {
        val stateField = ScreenDefinition::class.java.getDeclaredField("state")
        stateField.isAccessible = true
        val stateReference = stateField.get(definition) as AtomicReference<*>
        return checkNotNull(stateReference.get())
    }

    private fun ScreenDefinitionPayload.literalTitle(): String = (title as UiText.Literal).value
}
