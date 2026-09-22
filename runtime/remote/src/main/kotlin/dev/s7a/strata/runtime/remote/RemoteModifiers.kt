@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.projection.StandardModifierProjections
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Standard active client modifiers reconstructed from validated declaration properties.
 */
internal object RemoteModifiers {
    /**
     * Registers portable appearance and immediate activation behavior.
     */
    fun register(registry: RemoteRegistry) {
        listOf(
            SemanticsRole.Button,
            SemanticsRole.Text,
            SemanticsRole.TextField,
            SemanticsRole.TextArea,
            SemanticsRole.Tab,
            SemanticsRole.Checkbox,
            SemanticsRole.Slider,
            SemanticsRole.CycleButton,
            SemanticsRole.ProgressBar,
            SemanticsRole.SelectionList,
        ).forEach(registry::role)
        registry.modifier(BuiltinProjection.ComponentAction.type, { ProjectionFields(it).finish() }) { _, _ -> Modifier.Empty }
        StandardModifierProjections.register { type, decode ->
            registry.modifier(type, decode) { value, _ -> Modifier.Empty.then(value) }
        }
        registry.modifier(BuiltinProjection.Padding.type, ::insets) { value, _ -> Modifier.Empty.padding(value) }
        registry.modifier(BuiltinProjection.Background.type, ::color) { value, _ -> Modifier.Empty.background(value) }
        registry.modifier(BuiltinProjection.Semantics.type, { semantics(it, registry) }) { value, _ -> Modifier.Empty.semantics(value) }
        registry.modifier(BuiltinProjection.ObservedActivation.type, { value ->
            val fields = ProjectionFields(value)
            val result = fields.flag() to endpoint(fields.value())
            fields.finish()
            result
        }) { (enabled, endpoint), actions ->
            Modifier.Empty.onActivate(enabled) { actions.send(endpoint, BuiltinProjection.ObservedActivation.type, ProjectionValue.Absent) }
        }
        registry.modifier(BuiltinProjection.PrimaryPress.type, ::endpoint) { endpoint, actions ->
            Modifier.Empty.onPress { actions.send(endpoint, BuiltinProjection.PrimaryPress.type, ProjectionValue.Absent) }
        }
        registry.modifier(BuiltinProjection.ActivationKeys.type, ::endpoint) { endpoint, actions ->
            Modifier.Empty.onKeyPress { event ->
                if (event.key == KeyCode.Enter || event.key == KeyCode.Space) {
                    actions.send(endpoint, BuiltinProjection.ActivationKeys.type, ProjectionValue.Absent)
                    InputResult.Consumed
                } else {
                    InputResult.Ignored
                }
            }
        }
    }

    private fun insets(value: ProjectionValue): Insets {
        val fields = ProjectionFields(value)
        val result = Insets(fields.int(0..Int.MAX_VALUE), fields.int(0..Int.MAX_VALUE), fields.int(0..Int.MAX_VALUE), fields.int(0..Int.MAX_VALUE))
        fields.finish()
        return result
    }

    private fun color(value: ProjectionValue): ArgbColor {
        val fields = ProjectionFields(value)
        val result = ArgbColor(fields.int())
        fields.finish()
        return result
    }

    private fun endpoint(value: ProjectionValue): Long {
        val identity = requireNotNull(value as? ProjectionValue.Integer) { "Expected an event endpoint." }.value
        require(0 < identity) { "Event endpoints must be positive." }
        return identity
    }

    private fun semantics(
        value: ProjectionValue,
        registry: RemoteRegistry,
    ): Semantics {
        val fields = ProjectionFields(value)
        val label = RemoteProperties.optional(fields.value(), RemoteTextCodec::decode)
        val role =
            RemoteProperties.optional(fields.value(), decode = { value ->
                val roleFields = ProjectionFields(value)
                val type = ProjectionType(ResourceId(roleFields.text(), roleFields.text()), roleFields.int(1..Int.MAX_VALUE))
                roleFields.finish()
                registry.resolveRole(type)
            })
        val text = RemoteProperties.optional(fields.value(), RemoteTextCodec::decode)
        val disabled = fields.flag()
        val selected = RemoteProperties.optional(fields.value(), RemoteProperties::flag)
        val checked = RemoteProperties.optional(fields.value(), RemoteProperties::flag)
        fields.finish()
        return Semantics(label, role, text, disabled, selected, checked)
    }
}
