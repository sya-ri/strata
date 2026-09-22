@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.modifier.onCheckedChange
import dev.s7a.strata.modifier.onCycle
import dev.s7a.strata.modifier.onSliderChange
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteProperties.enumeration
import dev.s7a.strata.runtime.remote.RemoteProperties.flag
import dev.s7a.strata.runtime.remote.RemoteProperties.integer
import dev.s7a.strata.runtime.remote.RemoteProperties.optional
import dev.s7a.strata.runtime.remote.RemoteProperties.real
import dev.s7a.strata.runtime.remote.RemoteProperties.text
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap

/**
 * Standard client controls with stable optimistic state, sharing all local focus, dragging, and IME behavior.
 * Native component actions flush edits immediately; text edits are flushed by the host before business actions and each tick.
 */
internal object RemoteForms {
    /**
     * Installs typed control schemas and their state ownership tokens into one client registry.
     */
    fun register(registry: RemoteRegistry) {
        checkbox(registry)
        slider(registry)
        cycle(registry)
        textField(registry)
        val scroll = RemoteControl(ScrollState::class, BuiltinProjection.ScrollPosition.type, ::real, ProjectionValue::Real, { it.metrics.offset }, { state, value -> state.scrollTo(value) })
        textArea(registry, scroll)
        scroll(registry, scroll)
        RemoteVirtualLists.register(registry, scroll)
    }

    private fun checkbox(registry: RemoteRegistry) {
        val control = RemoteControl(CheckboxState::class, RemoteProfileComponent.Checkbox.type, ::flag, ProjectionValue::Flag, { it.checked }, { state, value -> state.checked = value })
        register(registry, RemoteProfileComponent.Checkbox) {
            val label = RemoteTextCodec.decode(value())
            val binding = RemoteBindingSnapshot.decode(value())
            val width = int(1..Int.MAX_VALUE)
            val enabled = flag()
            control.specification(binding, ::CheckboxState, { state, context ->
                require(context.children.isEmpty())
                val modifier = context.modifier.onCheckedChange { context.states.flushEdits(context.actions) }
                ComponentRuntimeBridge.currentRuntime().checkbox(label, state, width, enabled, modifier, context.key)
            })
        }
    }

    private fun slider(registry: RemoteRegistry) {
        val control = RemoteControl(SliderState::class, RemoteProfileComponent.Slider.type, ::real, ProjectionValue::Real, { it.value }, { state, value -> state.value = value })
        register(registry, RemoteProfileComponent.Slider) {
            val label = RemoteTextCodec.decode(value())
            val binding = RemoteBindingSnapshot.decode(value())
            val minimum = real()
            val maximum = real()
            require(minimum < maximum)
            val steps = int(0 until Int.MAX_VALUE)
            require(real(binding.value) in minimum..maximum)
            val width = int(1..Int.MAX_VALUE)
            val enabled = flag()
            control.specification(binding, { SliderState(it, minimum..maximum, steps) }, { state, context ->
                require(context.children.isEmpty())
                val modifier = context.modifier.onSliderChange { context.states.flushEdits(context.actions) }
                ComponentRuntimeBridge.currentRuntime().slider(label, state, width, enabled, modifier, context.key)
            })
        }
    }

    private fun cycle(registry: RemoteRegistry) {
        val control =
            RemoteControl(CycleButtonState::class, RemoteProfileComponent.CycleButton.type, ::integer, { ProjectionValue.Integer(it.toLong()) }, {
                it.values.indexOf(it.value)
            }, { state, index -> setCycleIndex(state, index) })
        register(registry, RemoteProfileComponent.CycleButton) {
            val binding = RemoteBindingSnapshot.decode(value())
            val labels = values().map(RemoteTextCodec::decode)
            require(labels.isNotEmpty() && integer(binding.value) in labels.indices)
            val width = int(1..Int.MAX_VALUE)
            val enabled = flag()
            control.specification(binding, { CycleButtonState(labels.indices.toList(), it) }, { state, context ->
                require(context.children.isEmpty())
                val modifier = context.modifier.onCycle<Int> { context.states.flushEdits(context.actions) }
                ComponentRuntimeBridge.currentRuntime().cycleButton(state, labels, width, enabled, modifier, context.key)
            })
        }
    }

    private fun textField(registry: RemoteRegistry) {
        val control = RemoteControl(TextFieldState::class, RemoteProfileComponent.TextField.type, ::text, ProjectionValue::Text, { it.value }, { state, value -> state.value = value })
        register(registry, RemoteProfileComponent.TextField) {
            val binding = RemoteBindingSnapshot.decode(value())
            val maximum = int(1..Int.MAX_VALUE)
            TextFieldState(text(binding.value), maximum)
            val size = RemoteProperties.size(value())
            val appearance = RemoteProperties.appearance(value())
            val enabled = flag()
            val style = enumeration<TextStyle>(this)
            val font = optional(value(), RemoteProperties::resource)
            control.specification(binding, { TextFieldState(it, maximum) }, { state, context ->
                require(context.children.isEmpty())
                val runtime = ComponentRuntimeBridge.currentRuntime()
                if (font == null) {
                    runtime.textField(state, size, appearance, enabled, style, context.modifier, context.key)
                } else {
                    runtime.textField(state, size, appearance, enabled, style, font, context.modifier, context.key)
                }
            })
        }
    }

    private fun textArea(
        registry: RemoteRegistry,
        scroll: RemoteControl<ScrollState, Double>,
    ) {
        val control = RemoteControl(TextAreaState::class, RemoteProfileComponent.TextArea.type, ::text, ProjectionValue::Text, { it.value }, { state, value -> state.value = value })
        register(registry, RemoteProfileComponent.TextArea) {
            val binding = RemoteBindingSnapshot.decode(value())
            val maximum = int(1..Int.MAX_VALUE)
            TextAreaState(text(binding.value), maximum)
            val viewport = RemoteProperties.viewport(value())
            val appearance = RemoteProperties.appearance(value())
            val enabled = flag()
            val style = enumeration<TextStyle>(this)
            val font = optional(value(), RemoteProperties::resource)
            val wrap = enumeration<TextWrap>(this)
            val spacing = int(0..Int.MAX_VALUE)
            val position = RemoteBindingSnapshot.decode(value())
            require(0.0 <= real(position.value))
            control.specification(binding, { TextAreaState(it, maximum) }, { state, context ->
                require(context.children.isEmpty())
                val runtime = ComponentRuntimeBridge.currentRuntime()
                if (font == null) {
                    runtime.textArea(state, viewport, appearance, enabled, style, wrap, spacing, context.modifier, context.key)
                } else {
                    runtime.textArea(state, viewport, appearance, enabled, style, font, wrap, spacing, context.modifier, context.key)
                }
            }, { state, context ->
                scroll.specification(position, { value -> state.scrollState.also { it.scrollTo(value) } }, { _, _ -> error("Scroll position has no standalone element.") }).prepare(context)
            })
        }
    }

    private fun scroll(
        registry: RemoteRegistry,
        control: RemoteControl<ScrollState, Double>,
    ) {
        register(registry, RemoteProfileComponent.ScrollArea) {
            val binding = RemoteBindingSnapshot.decode(value())
            require(0.0 <= real(binding.value))
            val rate = int(1..Int.MAX_VALUE)
            control.specification(binding, ::ScrollState, { state, context ->
                ComponentRuntimeBridge.currentRuntime().scrollArea(state, context.children.single(), rate, context.modifier, context.key)
            })
        }
        register(registry, RemoteProfileComponent.Scrollbar, RemotePreparationPhase.References) {
            val binding = RemoteBindingSnapshot.decode(value())
            require(0.0 <= real(binding.value))
            control.specification(binding, ::ScrollState, { state, context ->
                require(context.children.isEmpty())
                ComponentRuntimeBridge.currentRuntime().scrollbar(state, context.modifier, context.key)
            })
        }
    }

    private fun register(
        registry: RemoteRegistry,
        kind: RemoteProfileComponent,
        phase: RemotePreparationPhase = RemotePreparationPhase.Owners,
        decode: ProjectionFields.() -> RemoteControl.Specification,
    ) {
        registry.element(kind.type, { value ->
            val fields = ProjectionFields(value)
            val result = fields.decode()
            fields.finish()
            result
        }, { spec, context -> spec.prepare(context) }, phase) { spec, context -> spec.render(context) }
    }

    private fun <T : Any> setCycleIndex(
        state: CycleButtonState<T>,
        index: Int,
    ) {
        state.value = state.values[index]
    }
}
