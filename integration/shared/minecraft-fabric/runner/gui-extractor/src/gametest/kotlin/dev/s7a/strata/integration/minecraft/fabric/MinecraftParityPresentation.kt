package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.util.function.Predicate
import dev.s7a.strata.integration.minecraft.fabric.MinecraftPresentationObservation.Snapshot as RenderWork

/**
 * Loaded-client presentation ownership assertions shared by screenshot parity scenes.
 */
internal object MinecraftParityPresentation {
    /**
     * Detaches and closes an active Fabric screen on the client thread, checking release of presentation ownership.
     */
    internal fun closeFabricScreen(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = MinecraftClientScreenAccess.currentScreen(minecraft)
                if (current is FabricMinecraftScreen) {
                    runCanvasTestCleanup(
                        null,
                        { MinecraftClientScreenAccess.setScreen(minecraft, null) },
                        { assertFabricPresentationReleased(current) },
                        current::onClose,
                    )
                }
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is FabricMinecraftScreen).not() })
    }

    private fun assertFabricPresentationReleased(screen: FabricMinecraftScreen) {
        val fields = FabricMinecraftScreen::class.java.declaredFields.associateBy { field -> field.name }

        fun retained(name: String): Any? {
            val field = fields[name] ?: error("Fabric presentation field is missing: $name")
            check(field.trySetAccessible()) { "Fabric presentation field is inaccessible: $name" }
            return field.get(screen)
        }

        val portableFrames = checkNotNull(retained("portableFrames"))
        val portableCurrent = portableFrames.javaClass.getDeclaredField("current")
        check(portableCurrent.trySetAccessible()) { "The portable presentation cache is inaccessible." }
        require(portableCurrent.get(portableFrames) == null) { "A detached Fabric screen retained portable drawing or texture ownership." }
        require(retained("preparedCommands") == null) { "A detached Fabric screen retained its display list." }
        require(retained("preparedViewport") == null) { "A detached Fabric screen retained its prepared viewport." }
        require(retained("preparedInputs") == null) { "A detached Fabric screen retained prepared layers." }
        require(retained("pointerPosition") == null) { "A detached Fabric screen retained its native pointer position." }
        require(retained("pointerFrameCommands") == null) { "A detached Fabric screen retained pointer display-list ownership." }
    }

    /**
     * Checks retained presentation identity across clean frames and an equivalent replacement display list.
     */
    internal fun assertCleanFabricFrameReuse(context: ClientGameTestContext) {
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val state = readRenderWork(minecraft)
                0L < state.renderExtractions && state.inputs != null
            },
        )
        val before = renderWork(context)
        context.waitFor(Predicate<Minecraft> { minecraft -> before.renderExtractions < readRenderWork(minecraft).renderExtractions })
        val after = renderWork(context)
        require(before.commands === after.commands && before.inputs === after.inputs) { "Clean presentation must retain its exact prepared inputs." }
        require(before.pointerCommands === after.pointerCommands) { "A stationary pointer must retain its extracted display-list identity." }
        require(before.samePortable(after) && before.sameSources(after)) { "Clean presentation must retain all actual native texture owners." }
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = activeFabricScreen(minecraft)
                val commandsField = FabricMinecraftScreen::class.java.getDeclaredField("preparedCommands")
                check(commandsField.trySetAccessible())
                val commands = commandsField.get(screen) as List<*>
                commandsField.set(screen, ArrayList(commands))
            },
        )
        val changed = renderWork(context)
        context.waitFor(Predicate<Minecraft> { minecraft -> changed.renderExtractions < readRenderWork(minecraft).renderExtractions })
        val equivalent = renderWork(context)
        require(equivalent.inputs !== before.inputs) { "A different display-list snapshot must refresh preparation." }
        require(equivalent.samePortable(before) && equivalent.sameSources(before)) { "Equivalent commands must retain the actual texture owners." }
    }

    /**
     * Reads the active Fabric screen's test-owned presentation observation through the client scheduler.
     */
    internal fun renderWork(context: ClientGameTestContext): RenderWork =
        context.computeOnClient(
            FailableFunction<Minecraft, RenderWork, RuntimeException> { minecraft -> readRenderWork(minecraft) },
        )

    /**
     * Reads the active Fabric screen's presentation observation from the client thread.
     */
    internal fun readRenderWork(minecraft: Minecraft): RenderWork = MinecraftPresentationObservation.read(activeFabricScreen(minecraft))

    /**
     * Requires an active Fabric wrapper on the calling client thread.
     */
    internal fun activeFabricScreen(minecraft: Minecraft): FabricMinecraftScreen {
        val activeScreen = MinecraftClientScreenAccess.currentScreen(minecraft)
        return activeScreen as? FabricMinecraftScreen ?: error("The Fabric performance screen is not active.")
    }
}
