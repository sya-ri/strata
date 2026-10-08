package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.integration.minecraft.fabric.mixin.pacing.MinecraftNativePerformanceClientAccess
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Captures no unavailable option; legacy native selectors retain their original policy and owner.
 */
@Suppress("UNUSED_PARAMETER") // This compiled family has no inactivity setting to borrow.
internal fun Minecraft.captureNativeInactivity(lease: MinecraftNativePerformanceOptionLease, requested: MinecraftNativePerformancePacing.Inactivity) {
    check(isSameThread)
    require(requested == MinecraftNativePerformancePacing.Inactivity.MINIMIZED) { "This native target has no AFK option to reproduce." }
}

/**
 * Reads the actual private native selector through a remapped fixture-only invoker.
 */
internal fun Minecraft.nativePerformancePacing(): MinecraftNativePerformancePacing {
    check(isSameThread)
    val selected = (this as MinecraftNativePerformanceClientAccess).strataNativeFramerateLimit()
    val iconified = GLFW.glfwGetWindowAttrib(window.window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE
    return MinecraftNativePerformancePacing(
        MinecraftNativePerformancePacing.Inactivity.UNAVAILABLE,
        MinecraftNativePerformancePacing.Reason.UNAVAILABLE,
        selected,
        selected,
        MinecraftNativePerformancePacing.AppliedLimitSource.DIRECT_SELECTOR,
        iconified,
    )
}
