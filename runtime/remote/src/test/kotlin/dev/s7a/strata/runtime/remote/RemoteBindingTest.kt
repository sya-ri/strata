package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Confirms optimistic edits, explicit replacements, source replacement, and native state preservation.
 */
internal class RemoteBindingTest {
    @Test
    fun staleRepliesPreserveDraftsAndExplicitReplacementAdvancesGeneration() {
        var sequence = 0L
        val bindings = RemoteServerBindings(RemoteLimits()) { sequence }
        val source = Value("initial")
        val binding = binding(source)
        val scope = Scope(bindings)
        val initial = project(scope, binding)
        val local = Value("initial")
        var writes = 0
        val editing =
            RemoteEditingState(local, binding.type, initial, ::text, ProjectionValue::Text, Value::text) { state, value ->
                state.text = value
                writes += 1
            }
        val sent = mutableListOf<ProjectionValue>()
        val actions =
            RemoteClientActions { _, _, value ->
                sent.add(value)
                sequence += 1
                sequence
            }
        local.text = "first"
        editing.flushEdits(actions)
        local.text = "second"
        editing.flushEdits(actions)
        sequence = 1
        scope.actions.last().dispatch(sent.first())
        editing.reconcile(project(scope, binding))
        assertEquals("second", local.text)
        assertEquals(0, writes)
        sequence = 2
        scope.actions.last().dispatch(sent.last())
        editing.reconcile(project(scope, binding))
        assertEquals(0, writes)
        source.text = "reset"
        val replacement = project(scope, binding)
        assertEquals(initial.generation + 1, replacement.generation)
        editing.reconcile(replacement)
        assertEquals("reset", local.text)
        assertEquals(1, writes)
        scope.actions.last().dispatch(sent.last())
        assertEquals("reset", source.text)
        bindings.close()
    }

    @Test
    fun anOldSourceCannotEditANewSourceAtTheSameComponentPosition() {
        val bindings = RemoteServerBindings(RemoteLimits()) { 1 }
        val scope = Scope(bindings)
        val old = project(scope, binding(Value("old")))
        val current = Value("new")
        val fresh = project(scope, binding(current))
        assertNotEquals(old.identity, fresh.identity)
        scope.actions.last().dispatch(old.edit(ProjectionValue.Text("stale")))
        assertEquals("new", current.text)
        bindings.close()
    }

    private fun project(
        scope: Scope,
        binding: ProjectionBinding<String>,
    ): RemoteBindingSnapshot {
        scope.bindings.begin()
        val projected = RemoteBindingSnapshot.decode(scope.binding(binding))
        scope.bindings.commit()
        return projected
    }

    private fun binding(state: Value): ProjectionBinding<String> =
        ProjectionBinding(
            BuiltinProjection.PointerPress.type,
            state,
            { state.text },
            ProjectionValue::Text,
            ::text,
            { state.text = it },
        )

    private fun text(value: ProjectionValue): String = requireNotNull(value as? ProjectionValue.Text).value

    private class Value(
        var text: String,
    )

    private class Scope(
        val bindings: RemoteServerBindings,
    ) : ProjectionScope {
        override fun requireType(type: ProjectionType) = Unit

        override fun text(text: UiText): ProjectionValue = RemoteTextCodec.encode(text)

        override fun image(image: DrawImage): ProjectionValue = RemoteImageCodec().encode(ImageSource.Pixels(image))

        val actions = mutableListOf<ProjectionAction<*>>()

        override fun action(
            action: ProjectionAction<*>,
            key: ProjectionValue,
        ): Long {
            actions.add(action)
            return actions.size.toLong()
        }

        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = bindings.project(binding, this)
    }
}
