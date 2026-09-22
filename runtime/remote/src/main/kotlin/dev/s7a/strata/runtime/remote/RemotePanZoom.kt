package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue

/**
 * One registry-owned transform adapter shared by remote viewports and active navigation modifiers.
 * Gesture arbitration and capture use the existing synchronous client implementation.
 */
internal class RemotePanZoom {
    private val control =
        RemoteControl(
            PanZoomState::class,
            BuiltinProjection.PanZoom.type,
            ::decode,
            ::encode,
            { it.metrics.let { metrics -> metrics.center to metrics.zoom } },
            { state, (center, zoom) ->
                state.zoomTo(zoom)
                state.centerOn(center)
            },
        )

    /**
     * Installs active pan-and-zoom behavior using this registry's shared native state token.
     */
    fun register(registry: RemoteRegistry) {
        registry.statefulModifier(BuiltinProjection.PanZoom.type, { value ->
            val fields = ProjectionFields(value)
            val properties = properties(fields)
            val button =
                when (val index = fields.long()) {
                    0L -> {
                        PointerButton.Primary
                    }

                    1L -> {
                        PointerButton.Secondary
                    }

                    2L -> {
                        PointerButton.Middle
                    }

                    else -> {
                        require(index in 3L..Int.MAX_VALUE.toLong() + 3L)
                        PointerButton.Auxiliary((index - 3L).toInt())
                    }
                }
            val step = fields.real().also { require(1.0 < it) }
            fields.finish()
            Navigation(properties, button, step)
        }, { properties, context -> prepare(properties.transform, context) }) { properties, context ->
            Modifier.Empty.panZoom(get(properties.transform, context.states), properties.button, properties.step)
        }
    }

    /**
     * Decodes a shared transform and immutable limits before state preparation.
     */
    fun properties(fields: ProjectionFields): Properties {
        val binding = RemoteBindingSnapshot.decode(fields.value())
        val minimum = fields.real()
        val maximum = fields.real()
        require(0.0 < minimum && minimum <= maximum)
        require(decode(binding.value).second in minimum..maximum)
        return Properties(binding, minimum, maximum)
    }

    /**
     * Prepares the current transform without acquiring viewport geometry or rendering resources.
     */
    fun prepare(
        properties: Properties,
        context: RemotePreparationContext,
    ): PanZoomState = control.prepare(properties.binding, { (center, zoom) -> PanZoomState(center, zoom, properties.minimum, properties.maximum) }, context)

    /**
     * Reads the already prepared native state during declaration evaluation.
     */
    fun get(
        properties: Properties,
        states: RemoteClientStates,
    ): PanZoomState = control.get(properties.binding, states)

    private fun decode(value: ProjectionValue): Pair<DoubleOffset, Double> {
        val fields = ProjectionFields(value)
        val result = DoubleOffset(fields.real(), fields.real()) to fields.real()
        fields.finish()
        return result
    }

    private fun encode(value: Pair<DoubleOffset, Double>): ProjectionValue = ProjectionValue.Sequence(listOf(ProjectionValue.Real(value.first.x), ProjectionValue.Real(value.first.y), ProjectionValue.Real(value.second)))

    /**
     * Detached server transform identity and validated immutable limits.
     */
    class Properties(
        val binding: RemoteBindingSnapshot,
        val minimum: Double,
        val maximum: Double,
    )

    /**
     * Local pointer arbitration policy associated with one shared transform.
     */
    private class Navigation(
        val transform: Properties,
        val button: PointerButton,
        val step: Double,
    )
}
