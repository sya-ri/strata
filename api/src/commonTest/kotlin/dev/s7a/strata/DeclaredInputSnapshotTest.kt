@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Independent literal subscription policies and endpoint order across JVM and JavaScript projection consumers.
 */
class DeclaredInputSnapshotTest {
    @Test
    fun capturedPoliciesAndEndpointRecordsRemainIndependentWithoutInvokingHandlers() {
        var calls = 0
        val flags = sequence(ProjectionValue.Flag(false), ProjectionValue.Flag(true), ProjectionValue.Flag(false), ProjectionValue.Flag(false), ProjectionValue.Flag(false), ProjectionValue.Flag(false))
        val filter = sequence(sequence(number(KeyCode.Enter.value)), flags)
        val secondary = sequence(number(1))
        val cases =
            listOf(
                Modifier.Empty.onKeyPress(InputResult.Consumed, KeyboardInputFilter(setOf(KeyCode.Enter), KeyboardModifiers(control = true))) { calls += 1 } to sequence(sequence(number(0), filter), number(1)),
                Modifier.Empty.onPress(InputResult.Consumed, PointerButton.Secondary) { _, _ -> calls += 1 } to sequence(sequence(number(0), secondary), number(1)),
                Modifier.Empty.onCharacterInput(InputResult.Consumed) { calls += 1 } to sequence(number(0), number(1)),
                Modifier.Empty.onPreedit(InputResult.Ignored) { calls += 1 } to sequence(number(1), number(1)),
                Modifier.Empty.onCapturedPointerEvent(PointerButton.Secondary, { calls += 1 }) { _, _ -> calls += 1 } to sequence(secondary, number(1), number(2)),
            )
        cases.forEach { (modifier, expected) ->
            val scope = Scope()
            val projection = requireNotNull(modifier.elements().single().projection)
            val old = projection.encode(scope)
            val hash = old.hashCode()
            assertEquals(expected, old)
            assertEquals(List(expected.values.size - 1) { expected.values.first() }, scope.keys)
            scope.reset()
            assertEquals(expected, projection.encode(scope))
            assertEquals(expected, old)
            assertEquals(hash, old.hashCode())
        }
        assertEquals(0, calls)
    }

    private fun number(value: Int): ProjectionValue = ProjectionValue.Integer(value.toLong())

    private fun sequence(vararg values: ProjectionValue): ProjectionValue.Sequence = ProjectionValue.Sequence(values.toList())

    /**
     * Retains only the finite registration keys for an untimed assertion and clears them between encodes.
     */
    private class Scope : ProjectionScope {
        val keys = mutableListOf<ProjectionValue>()

        /**
         * Ends ownership of the previous callback-local registration keys.
         */
        fun reset() {
            keys.clear()
        }

        override fun action(action: ProjectionAction<*>, key: ProjectionValue): Long {
            keys.add(key)
            return keys.size.toLong()
        }

        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = error("Unexpected binding")

        override fun image(image: DrawImage): ProjectionValue = error("Unexpected image")

        override fun text(text: UiText): ProjectionValue = error("Unexpected text")

        override fun requireType(type: ProjectionType): Unit = error("Unexpected nested type")
    }
}
