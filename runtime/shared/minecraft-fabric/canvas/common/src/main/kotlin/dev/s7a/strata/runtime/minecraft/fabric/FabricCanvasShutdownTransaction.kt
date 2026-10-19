package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Orders borrowed owner-thread Canvas shutdown before the platform destroys its rendering context.
 * Screen failures do not skip queue discard or vanilla shutdown; device release requires a successful discard.
 * An original vanilla failure remains primary over independent cleanup failures.
 */
@InternalStrataRuntimeApi
public object FabricCanvasShutdownTransaction {
    /**
     * Stops callbacks, discards unconsumed GUI work, conditionally releases the device and always attempts vanilla close.
     * Runnable parameters preserve the existing Java entrypoint; all callbacks are borrowed only until return.
     */
    @JvmStatic
    @Throws(Throwable::class)
    public fun run(
        stopScreen: Runnable,
        discardGui: Runnable,
        releaseDevice: Runnable,
        closeVanilla: Runnable,
    ) {
        var cleanup = attempt(null, stopScreen)
        val discarded = runCatching { discardGui.run() }
        cleanup = add(cleanup, discarded.exceptionOrNull())
        if (discarded.isSuccess) cleanup = attempt(cleanup, releaseDevice)
        val vanilla = runCatching { closeVanilla.run() }.exceptionOrNull()
        if (vanilla != null) {
            if (cleanup != null) FabricMinecraftFailures.addSuppressed(vanilla, cleanup)
            throw vanilla
        }
        if (cleanup != null) throw cleanup
    }

    private fun attempt(
        primary: Throwable?,
        action: Runnable,
    ): Throwable? = add(primary, runCatching { action.run() }.exceptionOrNull())

    private fun add(
        primary: Throwable?,
        secondary: Throwable?,
    ): Throwable? {
        if (secondary == null) return primary
        if (primary == null) return secondary
        FabricMinecraftFailures.addSuppressed(primary, secondary)
        return primary
    }
}
