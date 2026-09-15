@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Exercises reactive content through the Minecraft host and its real component profile boundary.
 */
internal class MinecraftReactiveContentTest {
    @Test
    fun ifAndWhenReevaluateAcrossFramesAndReattachment() {
        val shown = mutableStateOf(true)
        val count = mutableStateOf(0)
        var evaluations = 0
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Reactive") {
                    evaluations += 1
                    Stack {
                        if (shown.value) {
                            Text(
                                when (count.value) {
                                    0 -> "First"
                                    else -> "Next"
                                },
                            )
                        }
                    }
                },
                MinecraftProfileFixture.create(),
            )
        val viewport = IntSize(100, 30)
        try {
            host.attach()
            assertCurrentLabel(host, viewport, "First")
            count.value = 1
            assertCurrentLabel(host, viewport, "Next")
            shown.value = false
            assertEquals(emptyList<Any>(), host.frame(viewport).semantics)
            count.value = 2
            host.frame(viewport)
            assertEquals(3, evaluations)
            host.detach()
            shown.value = true
            host.attach()
            assertCurrentLabel(host, viewport, "Next")
        } finally {
            host.close()
        }
        shown.value = false
    }

    @Test
    fun failureDuringReevaluationPreservesThePrimaryFailureAndReleasesState() {
        val fail = mutableStateOf(false)
        val expected = IllegalStateException("Reactive evaluation failed")
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Reactive failure") {
                    if (fail.value) throw expected
                    Stack { Text("Ready") }
                },
                MinecraftProfileFixture.create(),
            )
        host.attach()
        host.frame(IntSize(100, 30))
        fail.value = true
        assertSame(expected, assertThrows(IllegalStateException::class.java) { host.frame(IntSize(100, 30)) })
        host.close()
        fail.value = false
    }

    private fun assertCurrentLabel(
        host: MinecraftUiHost,
        viewport: IntSize,
        expected: String,
    ) {
        val semantics =
            host
                .frame(viewport)
                .semantics
                .single()
                .semantics
        assertEquals(UiText.Literal(expected), semantics.label)
    }
}
