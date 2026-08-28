package dev.s7a.strata.runtime.minecraft.font

/**
 * Identifies a bounded-input rejection so source enumeration can exclude only the affected pack.
 * Custom asset sources may throw this type when stopping enumeration at their supplied ceiling.
 * The exception retains a detached explanation and no source, stream, or failed input.
 *
 * @param message exceeded ceiling and observed amount.
 */
public class MinecraftFontLoadLimitException(
    message: String,
) : IllegalArgumentException(message)
