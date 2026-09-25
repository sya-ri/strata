package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.server.MinecraftServer

/**
 * Owns one disposable integrated-server world used by loaded-client verification.
 *
 * The handle borrows [server] while open, waits for runner-specific client readiness through [awaitReady], and disconnects and removes runner-owned resources from [close].
 */
internal interface MinecraftLoadedTestWorld : AutoCloseable {
    /** Integrated server borrowed until [close] returns. */
    val server: MinecraftServer

    /**
     * Evaluates [action] on the integrated server thread and waits for its result.
     *
     * @param action operation borrowing [server] for the duration of the call.
     * @return the operation result, whose ownership follows [action].
     * @throws Throwable when the runner cannot schedule the operation or [action] fails.
     */
    fun <T : Any> computeOnServer(action: (MinecraftServer) -> T): T

    /**
     * Waits until the client-side world is ready for UI verification.
     *
     * @throws AssertionError when the world cannot reach the runner's readiness gate.
     */
    fun awaitReady()

    /** Disconnects the client, stops the integrated server, and releases runner-owned world resources. */
    override fun close()
}
