@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Verifies public scheduling delegation and installation lifetime without the remote engine.
 */
internal class VelocityUiTest {
    @Test
    fun repeatedCloseDoesNotRemoveTheNextInstallation() {
        val owner = Any()
        val released = mutableListOf<Any>()
        val provider =
            object : VelocityUiProvider {
                override fun open(
                    ownerPlugin: Any,
                    player: Player,
                    definition: () -> UiDefinition,
                ): CompletableFuture<UiSession> = error("Opening is not requested by this test.")

                override fun capabilities(player: Player): CompletableFuture<UiClientCapabilities?> = CompletableFuture.completedFuture(null)

                override fun <T> execute(
                    ownerPlugin: Any,
                    operation: () -> T,
                ): CompletableFuture<T> {
                    assertSame(owner, ownerPlugin)
                    return CompletableFuture.completedFuture(operation())
                }

                override fun register(
                    ownerPlugin: Any,
                    type: ProjectionType,
                ): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

                override fun release(ownerPlugin: Any): CompletableFuture<Unit> {
                    released.add(ownerPlugin)
                    return CompletableFuture.completedFuture(Unit)
                }
            }
        val first = VelocityUi.install(provider)
        first.close()
        VelocityUi.install(provider).use {
            first.close()
            assertEquals(42, VelocityUi.execute(owner) { 42 }.join())
            assertThrows(IllegalStateException::class.java) { VelocityUi.install(provider) }
            VelocityUi.release(owner).join()
            assertEquals(listOf(owner), released)
        }
        assertThrows(IllegalStateException::class.java) { VelocityUi.execute(owner) { 0 } }
    }
}
