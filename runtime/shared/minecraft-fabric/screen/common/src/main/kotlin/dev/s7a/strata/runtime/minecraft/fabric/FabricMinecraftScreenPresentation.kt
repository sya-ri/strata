package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.screen.ScreenOpenThreadException

/**
 * Creates and installs an owned native screen after client-thread validation, without transferring a definition on the wrong thread.
 * The factory cleans up partial creation; after it returns, a failed installation closes the screen and preserves the installation failure.
 * Every callback is borrowed synchronously on the caller's thread and is never retained.
 */
internal fun <S : AutoCloseable> presentFabricScreen(
    isClientThread: () -> Boolean,
    create: () -> S,
    install: (S) -> Unit,
) {
    if (isClientThread().not()) throw ScreenOpenThreadException("Strata screens must be opened on the Minecraft client thread.")
    val screen = create()
    val failure = runCatching { install(screen) }.exceptionOrNull() ?: return
    val cleanup = runCatching { screen.close() }.exceptionOrNull()
    if (cleanup != null) FabricMinecraftFailures.addSuppressed(failure, cleanup)
    throw failure
}
