package dev.s7a.strata.runtime.remote

import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.actionDispatcher
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionScrollBinding
import dev.s7a.strata.projection.ProjectionValue

/**
 * Server-side standard value bindings validating remote input before state or business callbacks change.
 * Cycle values remain server-owned; only their positions and presentation labels cross the connection.
 */
internal object RemoteFormBindings {
    /**
     * Binds checked values to their caller-owned state and current component dispatcher.
     */
    fun checkbox(
        scope: ProjectionScope,
        state: CheckboxState,
        modifier: Modifier,
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(RemoteProfileComponent.Checkbox.type, state, { state.checked }, ProjectionValue::Flag, {
                requireNotNull(it as? ProjectionValue.Flag).value
            }) { value ->
                state.checked = value
                modifier.actionDispatcher().dispatch(ComponentActions.CheckedChange, value)
            },
        )

    /**
     * Binds finite numeric values and rejects values outside the authoritative range.
     */
    fun slider(
        scope: ProjectionScope,
        state: SliderState,
        modifier: Modifier,
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(RemoteProfileComponent.Slider.type, state, { state.value }, ProjectionValue::Real, {
                requireNotNull(it as? ProjectionValue.Real).value.also { value -> require(value in state.range) }
            }) { value ->
                state.value = value
                modifier.actionDispatcher().dispatch(ComponentActions.SliderChange, state.value)
            },
        )

    /**
     * Binds a checked option index to the exact current server model value.
     */
    fun <T : Any> cycle(
        scope: ProjectionScope,
        state: CycleButtonState<T>,
        modifier: Modifier,
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(RemoteProfileComponent.CycleButton.type, state, { state.values.indexOf(state.value) }, { ProjectionValue.Integer(it.toLong()) }, {
                val value = requireNotNull(it as? ProjectionValue.Integer).value
                require(value in 0L until state.values.size.toLong())
                value.toInt()
            }) { index ->
                state.value = state.values[index]
                modifier.actionDispatcher().dispatch(ComponentActions.Cycle, state.value)
            },
        )

    /**
     * Uses the existing single-line Unicode and length contract for inbound edits.
     */
    fun textField(
        scope: ProjectionScope,
        state: TextFieldState,
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(RemoteProfileComponent.TextField.type, state, { state.value }, ProjectionValue::Text, {
                TextFieldState(requireNotNull(it as? ProjectionValue.Text).value, state.maxLength).value
            }) { state.value = it },
        )

    /**
     * Uses the existing multiline normalization and length contract for inbound edits.
     */
    fun textArea(
        scope: ProjectionScope,
        state: TextAreaState,
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(RemoteProfileComponent.TextArea.type, state, { state.value }, ProjectionValue::Text, {
                TextAreaState(requireNotNull(it as? ProjectionValue.Text).value, state.maxLength).value
            }) { state.value = it },
        )

    /**
     * Shares one source identity between the viewport and any independently placed scrollbars.
     */
    fun scroll(
        scope: ProjectionScope,
        state: ScrollState,
    ): ProjectionValue = ProjectionScrollBinding.project(scope, state)
}
