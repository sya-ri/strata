package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import java.nio.file.Path

/**
 * Coordinates loaded-client verification without coupling the assertions to one Fabric test runner.
 *
 * Implementations own their runner's client-thread handoff and tick synchronization. Callers own values returned from [createSingleplayerWorld] and must close them.
 */
internal interface MinecraftLoadedTestContext {
    /**
     * Evaluates [action] on Minecraft's client thread and waits for its result.
     *
     * @param action operation borrowing the loaded client for the duration of the call.
     * @return the operation result, whose ownership follows [action].
     * @throws Throwable when the runner cannot schedule the operation or [action] fails.
     */
    fun <T : Any> computeOnClient(action: (Minecraft) -> T): T

    /**
     * Waits for [ticks] complete loaded-client ticks.
     *
     * @param ticks non-negative number of ticks to observe.
     * @throws IllegalArgumentException when [ticks] is negative.
     * @throws AssertionError when the runner stops producing ticks.
     */
    fun waitTicks(ticks: Int)

    /**
     * Waits until [condition] matches on the client thread.
     *
     * @param timeoutTicks positive maximum number of ticks to wait.
     * @param condition client-thread predicate evaluated at least once.
     * @throws IllegalArgumentException when [timeoutTicks] is not positive.
     * @throws AssertionError when the condition does not match before the timeout.
     */
    fun waitFor(
        timeoutTicks: Int = DEFAULT_TIMEOUT_TICKS,
        condition: (Minecraft) -> Boolean,
    )

    /**
     * Moves the loaded client's pointer to a GUI-scaled position.
     *
     * @param position GUI-space position within the configured viewport.
     * @throws Throwable when the runner cannot move the native or simulated pointer.
     */
    fun movePointer(position: IntOffset)

    /**
     * Captures the current loaded-client framebuffer as a PNG.
     *
     * @param name filename stem used for the captured image.
     * @param output existing destination directory owned by the suite.
     * @param size exact framebuffer dimensions required by the acceptance scene.
     * @return the captured PNG path owned by the caller.
     * @throws Throwable when capture fails or the framebuffer dimensions differ from [size].
     */
    fun takeScreenshot(
        name: String,
        output: Path,
        size: IntSize,
    ): Path

    /**
     * Creates and joins a disposable integrated-server world.
     *
     * @return an owned world handle that is ready for client/server verification after [MinecraftLoadedTestWorld.awaitReady].
     * @throws Throwable when world creation or joining fails.
     */
    fun createSingleplayerWorld(): MinecraftLoadedTestWorld

    private companion object {
        private const val DEFAULT_TIMEOUT_TICKS = 200
    }
}
