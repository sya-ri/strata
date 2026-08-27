package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates loaded-client verification on Minecraft 1.20 and 1.20.1 before Fabric Client GameTest.
 *
 * The context owns one tick signal registration for the process lifetime and schedules all Minecraft access on the client thread. Calls originate from the standalone test thread and fail when the client stops responding.
 */
internal class StandaloneMinecraftLoadedTestContext : MinecraftLoadedTestContext {
    private val tickSignal = Semaphore(0)

    init {
        ClientTickEvents.END_CLIENT_TICK.register { tickSignal.release() }
    }

    override fun <T : Any> computeOnClient(action: (Minecraft) -> T): T {
        val result = CompletableFuture<T>()
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            runCatching { action(minecraft) }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        return result.get(CLIENT_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun waitTicks(ticks: Int) {
        require(0 <= ticks) { "ticks cannot be negative" }
        tickSignal.drainPermits()
        repeat(ticks) {
            check(tickSignal.tryAcquire(TICK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for a loaded Minecraft client tick."
            }
        }
    }

    override fun waitFor(
        timeoutTicks: Int,
        condition: (Minecraft) -> Boolean,
    ) {
        require(0 < timeoutTicks) { "timeoutTicks must be positive" }
        repeat(timeoutTicks) {
            if (computeOnClient(condition)) return
            waitTicks(1)
        }
        check(computeOnClient(condition)) { "Timed out waiting for a loaded Minecraft client condition." }
    }

    override fun movePointer(position: IntOffset) {
        computeOnClient { minecraft ->
            val scale = minecraft.window.guiScale
            val rawX = position.x * scale
            val rawY = position.y * scale
            GLFW.glfwSetCursorPos(
                minecraft.window.window,
                rawX,
                rawY,
            )
            dispatchNativePointerMove(minecraft, rawX, rawY)
            Unit
        }
    }

    private fun dispatchNativePointerMove(
        minecraft: Minecraft,
        rawX: Double,
        rawY: Double,
    ) {
        val parameterTypes =
            arrayOf(
                checkNotNull(Long::class.javaPrimitiveType),
                checkNotNull(Double::class.javaPrimitiveType),
                checkNotNull(Double::class.javaPrimitiveType),
            )
        val handler = minecraft.mouseHandler
        val callbackName =
            FabricLoader
                .getInstance()
                .mappingResolver
                .mapMethodName("intermediary", "net.minecraft.class_312", "method_1600", "(JDD)V")
        val callback =
            handler.javaClass.declaredMethods.single { method ->
                method.name == callbackName && method.parameterTypes.contentEquals(parameterTypes)
            }
        check(callback.trySetAccessible()) { "The native Minecraft pointer callback is inaccessible." }
        callback.invoke(handler, minecraft.window.window, rawX, rawY)
    }

    override fun takeScreenshot(
        name: String,
        output: Path,
        size: IntSize,
    ): Path {
        val destination = output.resolve("$name.png")
        computeOnClient { minecraft ->
            Screenshot.takeScreenshot(minecraft.mainRenderTarget).use { image ->
                require(image.getWidth() == size.width && image.getHeight() == size.height) {
                    "The loaded client did not honor the configured verification viewport."
                }
                image.writeToFile(destination)
            }
        }
        return destination
    }

    override fun createSingleplayerWorld(): MinecraftLoadedTestWorld {
        computeOnClient { minecraft -> CreateWorldScreen.openFresh(minecraft, minecraft.screen) }
        waitFor(WORLD_TIMEOUT_TICKS) { minecraft -> minecraft.screen is CreateWorldScreen }
        waitTicks(2)

        val worldId =
            computeOnClient { minecraft ->
                val screen = minecraft.screen as? CreateWorldScreen ?: error("The create-world screen is not active.")
                screen.uiState.setName(WORLD_NAME)
                val targetFolder = screen.uiState.targetFolder
                pressButton(screen, "selectWorld.create")
                targetFolder
            }
        waitFor(WORLD_TIMEOUT_TICKS) { minecraft ->
            minecraft.level != null && minecraft.player != null && minecraft.singleplayerServer != null
        }
        val integratedServer = computeOnClient { minecraft -> checkNotNull(minecraft.singleplayerServer) }
        return StandaloneMinecraftLoadedTestWorld(this, integratedServer, worldId)
    }

    private fun pressButton(
        screen: CreateWorldScreen,
        translationKey: String,
    ) {
        val expected = Component.translatable(translationKey).string
        val match = AtomicReference<AbstractButton?>()
        screen.children().forEach { child ->
            (child as? AbstractWidget)?.visitWidgets { widget ->
                if (match.get() == null && widget is AbstractButton && widget.message.string == expected) {
                    match.set(widget)
                }
            }
        }
        checkNotNull(match.get()) { "Could not find the create-world button." }.onPress()
    }

    private companion object {
        private const val CLIENT_TASK_TIMEOUT_SECONDS = 120L
        private const val TICK_TIMEOUT_SECONDS = 30L
        private const val WORLD_TIMEOUT_TICKS = 1_200
        private const val WORLD_NAME = "Strata integration verification"
    }
}
