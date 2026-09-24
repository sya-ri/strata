@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

/**
 * Exercises the public facade with only platform and Strata API dependencies.
 */
internal class PaperUiTest {
    @Test
    @Suppress("StringLiteralComparison") // Java proxy dispatch decodes reflected method names at this test adapter boundary.
    fun registrationLifetimeCannotRemoveAReinstalledProvider() {
        val ownerThread = Thread.currentThread()
        val player = proxy<Player> { null }
        val plugin =
            proxy<Plugin> { name ->
                when (name) {
                    "isEnabled" -> true
                    else -> null
                }
            }
        val type = ProjectionType(ResourceId("example", "event"))
        val support = UiClientCapabilities(setOf(type), 16)
        var registered: ProjectionType? = null
        val provider =
            object : PaperUiProvider {
                override fun capabilities(player: Player): UiClientCapabilities {
                    check(Thread.currentThread() === ownerThread)
                    return support
                }

                override fun open(
                    ownerPlugin: Plugin,
                    player: Player,
                    definition: () -> UiDefinition,
                ): UiSession = error("Opening is not requested by this test.")

                override fun <T> execute(
                    player: Player,
                    operation: () -> T,
                ): T {
                    check(Thread.currentThread() === ownerThread)
                    return operation()
                }

                override fun register(
                    ownerPlugin: Plugin,
                    type: ProjectionType,
                ) {
                    assertSame(plugin, ownerPlugin)
                    registered = type
                }
            }
        assertNull(PaperUi.capabilities(player))
        val first = PaperUi.install(provider)
        first.close()
        PaperUi.install(provider).use {
            first.close()
            assertSame(support, PaperUi.capabilities(player))
            assertEquals(7, PaperUi.execute(player) { 7 })
            PaperUi.register(plugin, type)
            assertEquals(type, registered)
            assertThrows(IllegalStateException::class.java) { PaperUi.install(provider) }
            CompletableFuture
                .runAsync {
                    assertThrows(IllegalStateException::class.java) { PaperUi.capabilities(player) }
                }.join()
        }
        assertNull(PaperUi.capabilities(player))
    }

    private inline fun <reified T> proxy(crossinline result: (String) -> Any?): T = T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> result(method.name) })
}
