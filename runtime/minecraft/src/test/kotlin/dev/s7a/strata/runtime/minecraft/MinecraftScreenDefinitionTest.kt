package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies reusable screen-definition metadata and content ownership boundaries.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftScreenDefinitionTest {
    @Test
    fun constructionRetainsExactMetadataWithoutEvaluatingContent() {
        val title = UiText.Translated("screen.strata.test")
        var contentCalls = 0
        val paused =
            createMinecraftScreenDefinition(title, pausesGame = true) {
                contentCalls += 1
                MinecraftHostProbe().element()
            }
        val unpaused =
            createMinecraftScreenDefinition(title, pausesGame = false) {
                contentCalls += 1
                MinecraftHostProbe().element()
            }

        assertSame(title, paused.title)
        assertSame(title, unpaused.title)
        assertEquals(true, paused.pausesGame)
        assertEquals(false, unpaused.pausesGame)
        assertEquals(0, contentCalls)
    }

    @Test
    fun hostConstructionAndCloseBeforeAttachNeverEvaluateContent() {
        var contentCalls = 0
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("lazy"), pausesGame = false) {
                contentCalls += 1
                MinecraftHostProbe().element()
            }

        val host = createMinecraftUiHost(definition)
        assertEquals(0, contentCalls)
        host.close()
        host.close()
        assertEquals(0, contentCalls)
    }

    @Test
    fun reusableDefinitionCreatesIndependentSessions() {
        val probe = MinecraftHostProbe()
        var contentCalls = 0
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("reusable"), pausesGame = true) {
                contentCalls += 1
                probe.element()
            }
        val first = createMinecraftUiHost(definition)

        first.attach()
        first.close()
        val second = createMinecraftUiHost(definition)
        second.attach()

        assertEquals(2, contentCalls)
        assertEquals(2, probe.nodes.size)
        assertNotSame(probe.nodes[0], probe.nodes[1])
        second.close()
    }

    @Test
    fun definitionRemainsReusableAfterAnIndependentHostFails() {
        val contentFailure = IllegalStateException("first-host")
        val probe = MinecraftHostProbe()
        var contentCalls = 0
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("failure"), pausesGame = false) {
                contentCalls += 1
                if (contentCalls == 1) {
                    throw contentFailure
                }
                probe.element()
            }
        val failingHost = createMinecraftUiHost(definition)

        assertSame(contentFailure, assertThrows(IllegalStateException::class.java) { failingHost.attach() })
        failingHost.close()
        val recoveredHost = createMinecraftUiHost(definition)
        recoveredHost.attach()

        assertEquals(2, contentCalls)
        assertEquals(1, probe.nodes.size)
        recoveredHost.close()
    }
}
