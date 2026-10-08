package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason
import net.minecraft.client.InactivityFpsLimit
import net.minecraft.client.Minecraft

/**
 * Captures the exact supported native inactivity option without mutating the client.
 */
internal fun Minecraft.captureNativeInactivity(lease: MinecraftNativePerformanceOptionLease, requested: MinecraftNativePerformancePacing.Inactivity) {
    check(isSameThread)
    val borrowed =
        when (requested) {
            MinecraftNativePerformancePacing.Inactivity.MINIMIZED -> InactivityFpsLimit.MINIMIZED
            MinecraftNativePerformancePacing.Inactivity.AFK -> InactivityFpsLimit.AFK
            MinecraftNativePerformancePacing.Inactivity.UNAVAILABLE -> error("Unavailable is not a borrowed native option.")
        }
    val option = options.inactivityFpsLimit()
    lease.capture(option::get, option::set, borrowed)
}

/**
 * Reads live selected pacing and the exact limiter input used by this compiled native family.
 */
internal fun Minecraft.nativePerformancePacing(): MinecraftNativePerformancePacing {
    check(isSameThread)
    val tracker = framerateLimitTracker
    val selected = tracker.framerateLimit
    val mode =
        when (checkNotNull(options.inactivityFpsLimit().get())) {
            InactivityFpsLimit.MINIMIZED -> MinecraftNativePerformancePacing.Inactivity.MINIMIZED
            InactivityFpsLimit.AFK -> MinecraftNativePerformancePacing.Inactivity.AFK
        }
    val reason =
        when (checkNotNull(tracker.throttleReason)) {
            FramerateThrottleReason.NONE -> MinecraftNativePerformancePacing.Reason.NONE
            FramerateThrottleReason.WINDOW_ICONIFIED -> MinecraftNativePerformancePacing.Reason.WINDOW_ICONIFIED
            FramerateThrottleReason.LONG_AFK -> MinecraftNativePerformancePacing.Reason.LONG_AFK
            FramerateThrottleReason.SHORT_AFK -> MinecraftNativePerformancePacing.Reason.SHORT_AFK
            FramerateThrottleReason.OUT_OF_LEVEL_MENU -> MinecraftNativePerformancePacing.Reason.OUT_OF_LEVEL_MENU
        }
    val applied = selected
    return MinecraftNativePerformancePacing(
        mode,
        reason,
        selected,
        applied,
        MinecraftNativePerformancePacing.AppliedLimitSource.DIRECT_SELECTOR,
        window.isIconified,
    )
}
