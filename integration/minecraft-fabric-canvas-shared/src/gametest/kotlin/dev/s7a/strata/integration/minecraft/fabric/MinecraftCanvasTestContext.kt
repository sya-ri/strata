package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Path

/**
 * Adapts loaded-client scheduling and native screenshots for Canvas acceptance across game versions.
 *
 * Scheduling and screenshot calls originate from the runner thread; implementations marshal work to its owning client thread.
 * Screen mutation is explicitly client-thread-only so consumer callbacks never recursively schedule runner work.
 * Screenshot output belongs to the runner's contained build directory and is recreated for each execution.
 * Scheduling, native rendering, timeout, and file failures propagate without replacement.
 */
internal interface MinecraftCanvasTestContext {
    /**
     * Immutable path to the runner-owned contained build directory receiving acceptance artifacts.
     *
     * Reading the path is thread-safe; each writer owns its distinct output files and propagates file failures.
     */
    val outputDirectory: Path

    /**
     * Runs [action] on the client thread and returns its result or exact failure.
     */
    fun <T : Any> onClient(action: () -> T): T

    /**
     * Replaces the current native screen from the client thread without scheduling another client task.
     *
     * The native GUI owns the displayed [screen]; null removes it through the normal native removal lifecycle.
     * This method is valid inside [onClient] and native consumer callbacks, and propagates lifecycle failures unchanged.
     */
    fun setScreen(screen: Screen?)

    /**
     * Reports whether the native GUI has an overlay, from the client thread without scheduling.
     *
     * The overlay remains owned by Minecraft; callers use this observation to avoid sending input while loading hides the screen.
     */
    fun hasOverlay(): Boolean

    /**
     * Delivers a primary-button press through the borrowed [screen]'s native callback on the client thread.
     *
     * [position] is an unclamped logical coordinate. The result is native consumption, and callback failures propagate unchanged.
     */
    fun pressPointer(
        screen: Screen,
        position: IntOffset,
    ): Boolean

    /**
     * Delivers a primary-button drag through the borrowed [screen]'s native callback on the client thread.
     *
     * [position] and [delta] use logical coordinates without clamping. Consumption and callback failures are returned unchanged.
     */
    fun dragPointer(
        screen: Screen,
        position: IntOffset,
        delta: IntOffset,
    ): Boolean

    /**
     * Delivers a primary-button release through the borrowed [screen]'s native callback on the client thread.
     *
     * [position] is an unclamped logical coordinate. Consumption and callback failures are returned unchanged.
     */
    fun releasePointer(
        screen: Screen,
        position: IntOffset,
    ): Boolean

    /**
     * Invokes the native window-focus callback with its real window handle on the client thread.
     *
     * This exercises the native focus-state update and installed input-reset mixin, without claiming an operating-system focus transition.
     * The caller restores the prior focus state; reset and cleanup failures propagate without replacement.
     */
    fun setWindowFocused(focused: Boolean)

    /**
     * Applies a positive viewport size and explicit GUI scale through the version-owned loaded-test boundary.
     *
     * The adapter may keep the native surface stable when its backend cannot safely resize that surface during one client-test process.
     */
    fun configureViewport(
        size: IntSize,
        guiScale: Int,
    )

    /**
     * Waits on the runner thread until the client-thread predicate succeeds or the runner times out.
     */
    fun waitFor(condition: () -> Boolean)

    /**
     * Advances the loaded client without blocking its render thread.
     */
    fun waitTicks(ticks: Int)

    /**
     * Captures actual native pixels at [size], returning the newly written PNG path.
     */
    fun takeScreenshot(
        name: String,
        size: IntSize,
    ): Path
}
