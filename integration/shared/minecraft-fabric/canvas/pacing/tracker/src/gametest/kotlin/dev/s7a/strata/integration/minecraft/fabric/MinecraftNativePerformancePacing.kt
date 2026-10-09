package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.InactivityFpsLimit
import net.minecraft.client.Minecraft

/**
 * Captures the exact supported native inactivity option without mutating the client.
 */
internal fun Minecraft.captureNativeInactivity(
    lease: MinecraftNativePerformanceOptionLease,
    requested: MinecraftNativePerformancePacing.Inactivity,
) {
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
    val reason = MinecraftNativePerformancePacing.Reason.UNAVAILABLE
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
