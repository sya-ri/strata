package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies one-shot definition ownership and lazy content evaluation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftScreenDefinitionTest {
    @Test
    fun constructionIsLazyAndCloseDoesNotEvaluateContent() {
        var contentCalls = 0
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("lazy")) {
                contentCalls += 1
                MinecraftHostProbe().element()
            }

        definition.close()
        definition.close()
        assertEquals(0, contentCalls)
        assertThrows(IllegalStateException::class.java) {
            createMinecraftUiHost(definition, MinecraftProfileFixture.create())
        }
    }

    @Test
    fun transferExposesMetadataAndEvaluatesContentOnlyOnAttach() {
        val title = UiText.Translated("screen.strata.test")
        val profile = MinecraftProfileFixture.create()
        val probe = MinecraftHostProbe()
        var contentCalls = 0
        val definition =
            createMinecraftScreenDefinition(title, pausesGame = true) {
                contentCalls += 1
                probe.element()
            }
        val host = createMinecraftUiHost(definition, profile)

        assertSame(title, host.title)
        assertEquals(true, host.pausesGame)
        assertEquals(0, contentCalls)
        host.attach()
        assertEquals(1, contentCalls)
        host.close()
        assertThrows(IllegalStateException::class.java) { host.title }
    }

    @Test
    fun definitionCanBeTransferredExactlyOnce() {
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("one")) {
                buildUi { Text("A") }
            }
        val profile = MinecraftProfileFixture.create()
        val host = createMinecraftUiHost(definition, profile)
        val transferredState = readDefinitionState(definition)

        assertPayloadReleased(definition)
        assertThrows(IllegalStateException::class.java) {
            createMinecraftUiHost(definition, profile)
        }
        definition.close()
        assertSame(transferredState, readDefinitionState(definition))
        host.close()
    }

    @Test
    fun transferAndCloseHaveExactlyOneWinner() {
        val transferredStateClass = transferredStateClass()
        val closedStateClass = closedStateClass()
        repeat(20) {
            val definition =
                createMinecraftScreenDefinition(UiText.Literal("race")) {
                    buildUi { Text("A") }
                }
            val profile = MinecraftProfileFixture.create()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val transfer =
                FutureTask<Boolean> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    runCatching {
                        val host = createMinecraftUiHost(definition, profile)
                        host.close()
                    }.isSuccess
                }
            val close =
                FutureTask<Boolean> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    definition.close()
                    true
                }
            val transferThread = Thread(transfer)
            val closeThread = Thread(close)
            transferThread.start()
            closeThread.start()
            assertEquals(true, ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(true, close.get(5, TimeUnit.SECONDS))
            val transferred = transfer.get(5, TimeUnit.SECONDS)
            val expectedStateClass = if (transferred) transferredStateClass else closedStateClass
            assertSame(expectedStateClass, readDefinitionState(definition).javaClass)
            assertPayloadReleased(definition)
        }
    }

    @Test
    fun closingAnAvailableDefinitionDropsItsPayload() {
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("closed")) {
                MinecraftHostProbe().element()
            }
        definition.close()
        assertSame(closedStateClass(), readDefinitionState(definition).javaClass)
        assertPayloadReleased(definition)
        assertThrows(IllegalStateException::class.java) {
            createMinecraftUiHost(definition, MinecraftProfileFixture.create())
        }
    }

    private fun transferredStateClass(): Class<*> {
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("transferred-state")) {
                MinecraftHostProbe().element()
            }
        val host = createMinecraftUiHost(definition, MinecraftProfileFixture.create())
        val stateClass = readDefinitionState(definition).javaClass
        host.close()
        return stateClass
    }

    private fun closedStateClass(): Class<*> {
        val definition =
            createMinecraftScreenDefinition(UiText.Literal("closed-state")) {
                MinecraftHostProbe().element()
            }
        definition.close()
        return readDefinitionState(definition).javaClass
    }

    private fun assertPayloadReleased(definition: MinecraftScreenDefinition) {
        val state = readDefinitionState(definition)
        assertTrue(state.javaClass.declaredFields.none { field -> field.type == TransferredMinecraftDefinition::class.java })
    }

    private fun readDefinitionState(definition: MinecraftScreenDefinition): Any {
        val stateField = definition.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        val reference = stateField.get(definition) as AtomicReference<*>
        return checkNotNull(reference.get())
    }
}
