package dev.s7a.strata

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinitionUnavailableException
import dev.s7a.strata.screen.ScreenRuntimeUnavailableException
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.UiPresenter
import dev.s7a.strata.spi.UiPresenters
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiScreenKind
import dev.s7a.strata.ui.UiVisibility
import dev.s7a.strata.ui.UiVisibilityPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Definition ownership and category precedence without a loaded platform.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class UiDefinitionTest {
    @Test
    fun defaultsUseScreenAndTranslatedNarrationAndTransferOnlyOnce() {
        var evaluated = 0
        val definition = UiDefinition { evaluated++ }
        val payload = definition.transfer()
        assertEquals(UiPresentation.Screen, payload.presentation)
        assertInstanceOf(UiText.Translated::class.java, payload.title)
        assertEquals(0, evaluated)
        definition.close()
        assertThrows(ScreenDefinitionUnavailableException::class.java) { definition.transfer() }
        val discarded = UiDefinition {}
        discarded.close()
        discarded.close()
        assertThrows(ScreenDefinitionUnavailableException::class.java) { discarded.transfer() }
    }

    @Test
    fun staleRegistrationCannotRemoveTheSamePresenterInstalledAgain() {
        val presenter = UiPresenter { error("presented") }
        val retired = UiPresenters.install(presenter)
        retired.close()
        UiPresenters.install(presenter).use {
            retired.close()
            UiDefinition {}.use { definition ->
                assertEquals("presented", assertThrows(IllegalStateException::class.java) { definition.open() }.message)
            }
        }
        val available = UiDefinition {}
        assertThrows(ScreenRuntimeUnavailableException::class.java) { available.open() }
        assertEquals(UiPresentation.Screen, available.transfer().presentation)
    }

    @Test
    fun categoryOverridesScreenKindAndCallerCannotMutateRules() {
        val category = UiCategory(ResourceId("example", "map"))
        val overrides = mutableMapOf(category to UiVisibility.Close)
        val policy = UiVisibilityPolicy(categories = overrides, screens = mapOf(UiScreenKind.Strata to UiVisibility.KeepVisible))
        overrides.clear()
        assertEquals(UiVisibility.Close, policy.resolve(UiScreenKind.Strata, category))
        assertEquals(UiVisibility.KeepVisible, policy.resolve(UiScreenKind.Strata))
        assertEquals(UiVisibility.Hide, policy.resolve(UiScreenKind.Chat))
        assertEquals(UiVisibility.KeepVisible, policy.resolve(null, category))
    }
}
