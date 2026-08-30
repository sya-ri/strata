package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread dispatcher that validates a complete mixed display list before partial native output.
 *
 * The containing presentation owns this bounded renderer list and discards it after submission.
 * It stores no command history and preserves the existing portable/native order and presenter-owned clips.
 * Exactly one renderer must accept each platform payload; unsupported or ambiguous payloads are rejected explicitly.
 *
 * @param renderers adapter-owned handlers, copied defensively without transferring ownership.
 * @param T native drawing context borrowed by each dispatch.
 */
@InternalStrataRuntimeApi
public class MinecraftPlatformCommands<T>(
    renderers: List<MinecraftPlatformCommandRenderer<T>>,
) {
    private val renderers = renderers.toList()

    /**
     * Validates every platform command without drawing or retaining the supplied list.
     *
     * @param commands the complete ordered frame, not only its visible native subset.
     * @throws IllegalArgumentException when any platform payload is unsupported or ambiguous, before drawing begins.
     * @throws Throwable when a handler detects an expired or foreign payload.
     */
    public fun validate(commands: List<DrawCommand>) {
        commands.filterIsInstance<DrawCommand.Platform>().forEach(::renderer)
    }

    /**
     * Dispatches one validated command at its unchanged display-list position.
     *
     * @param target borrowed version-owned GUI context with the appropriate active clip.
     * @param command immutable platform payload and logical bounds.
     * @throws Throwable when renderer selection or native drawing fails.
     */
    public fun render(
        target: T,
        command: DrawCommand.Platform,
    ) {
        renderer(command).render(target, command)
    }

    private fun renderer(command: DrawCommand.Platform): MinecraftPlatformCommandRenderer<T> =
        requireNotNull(renderers.singleOrNull { it.accepts(command.command) }) {
            "A platform command must have exactly one matching native renderer."
        }
}
