package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies extensible native dispatch selection without claiming execution by a loaded Minecraft presenter.
 * The outer presenter owns portable commands, clip state, and the actual GUI-consumption boundary.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPlatformCommandsTest {
    @Test
    fun copiedHandlersPreserveBorrowedContextLogicalBoundsAndExplicitDispatchOrder() {
        val first = ProbeRenderer { it === Payload.First }
        val second = ProbeRenderer { it === Payload.Second }
        val handlers = mutableListOf<MinecraftPlatformCommandRenderer<Any>>(first, second)
        val dispatch = MinecraftPlatformCommands(handlers)
        handlers.clear()
        val firstCommand = DrawCommand.Platform(Payload.First, IntRect(11, 13, 19, 18))
        val secondCommand = DrawCommand.Platform(Payload.Second, IntRect(3, 4, 9, 6))
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 24, 24), ArgbColor(0xFF000000.toInt())),
                firstCommand,
                DrawCommand.PushClip(IntRect(0, 0, 12, 12)),
                secondCommand,
                DrawCommand.PopClip,
            )
        val original = commands.toList()
        dispatch.validate(commands)
        assertTrue(first.rendered.isEmpty())
        assertTrue(second.rendered.isEmpty())
        assertEquals(original, commands)

        val context = Any()
        dispatch.render(context, firstCommand)
        assertSame(firstCommand, first.rendered.single())
        assertTrue(second.rendered.isEmpty())
        dispatch.render(context, secondCommand)
        assertSame(secondCommand, second.rendered.single())
        assertSame(context, first.contexts.single())
        assertSame(context, second.contexts.single())
        assertEquals(IntRect(11, 13, 19, 18), first.rendered.single().bounds)
        assertEquals(IntRect(3, 4, 9, 6), second.rendered.single().bounds)
    }

    @Test
    fun completePreflightRejectsUnsupportedClippedCommandsAndAmbiguousHandlersWithoutDrawing() {
        val first = ProbeRenderer { it === Payload.First }
        val dispatch = MinecraftPlatformCommands(listOf(first))
        val firstCommand = DrawCommand.Platform(Payload.First, IntRect(0, 0, 2, 2))
        val unsupported = DrawCommand.Platform(Payload.Second, IntRect(20, 20, 22, 22))
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 24, 24), ArgbColor(0xFF000000.toInt())),
                firstCommand,
                DrawCommand.PushClip(IntRect(0, 0, 1, 1)),
                unsupported,
                DrawCommand.PopClip,
            )
        assertThrows(IllegalArgumentException::class.java) { dispatch.validate(commands) }
        assertTrue(first.rendered.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { dispatch.render(Any(), unsupported) }
        assertTrue(first.rendered.isEmpty())

        val overlapping = ProbeRenderer { it === Payload.First }
        val ambiguous = MinecraftPlatformCommands(listOf(first, overlapping))
        assertThrows(IllegalArgumentException::class.java) { ambiguous.validate(listOf(firstCommand)) }
        assertThrows(IllegalArgumentException::class.java) { ambiguous.render(Any(), firstCommand) }
        assertTrue(first.rendered.isEmpty())
        assertTrue(overlapping.rendered.isEmpty())
    }

    @Test
    fun expiredPayloadValidationPreservesTheOriginalFailureBeforeAnyNativeDrawing() {
        val failure = IllegalStateException("expired platform generation")
        val first = ProbeRenderer { it === Payload.First }
        val rejecting = ProbeRenderer { throw failure }
        val dispatch = MinecraftPlatformCommands(listOf(first, rejecting))
        val command = DrawCommand.Platform(Payload.First, IntRect(0, 0, 2, 2))

        assertSame(failure, assertThrows(IllegalStateException::class.java) { dispatch.validate(listOf(command)) })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { dispatch.render(Any(), command) })
        assertTrue(first.rendered.isEmpty())
        assertTrue(rejecting.rendered.isEmpty())
    }

    @Test
    fun selectedRendererFailurePropagatesWithoutFallingThroughToAnotherHandler() {
        val failure = IllegalArgumentException("native drawing")
        val first = ProbeRenderer { it === Payload.First }
        val second = ProbeRenderer { it === Payload.Second }
        first.renderFailure = failure
        val dispatch = MinecraftPlatformCommands(listOf(first, second))
        val command = DrawCommand.Platform(Payload.First, IntRect(0, 0, 2, 2))
        dispatch.validate(listOf(command))

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { dispatch.render(Any(), command) })
        assertEquals(listOf(command), first.rendered)
        assertTrue(second.rendered.isEmpty())
    }

    private class ProbeRenderer(
        private val acceptsPayload: (PlatformDrawCommand) -> Boolean,
    ) : MinecraftPlatformCommandRenderer<Any> {
        val contexts = ArrayList<Any>()
        val rendered = ArrayList<DrawCommand.Platform>()
        var renderFailure: Throwable? = null

        override fun accepts(command: PlatformDrawCommand): Boolean = acceptsPayload(command)

        override fun render(
            target: Any,
            command: DrawCommand.Platform,
        ) {
            contexts += target
            rendered += command
            renderFailure?.let { throw it }
        }
    }

    private enum class Payload : PlatformDrawCommand {
        First,
        Second,
    }
}
