package dev.s7a.strata.runtime.minecraft

/**
 * One-shot declarative definition for one Minecraft screen.
 *
 * The definition owns its title, pause policy, content evaluator, and caller capture graph until one host atomically takes them or [close] releases them.
 * Construction does not evaluate content.
 * Closing and host transfer may race from different threads, but exactly one operation owns the values and no value is transferred twice.
 * Definitions have referential identity and do not define value equality.
 */
public sealed interface MinecraftScreenDefinition : AutoCloseable {
    /**
     * Releases an untransferred definition.
     *
     * Close is thread-safe and idempotent.
     * Closing a definition after successful host transfer is a no-op.
     */
    override fun close()
}
