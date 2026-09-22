@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Typed standard layout factories using the same compiled DSL as local screens.
 */
internal object RemoteLayouts {
    /**
     * Installs all currently projected standard layout schemas into an unfrozen registry.
     */
    fun register(registry: RemoteRegistry) {
        registry.element(BuiltinProjection.Spacer.type, { integers(it, 0) }) { _, context ->
            require(context.children.isEmpty()) { "A Spacer cannot contain children." }
            evaluateComponentTree { Spacer(context.modifier, context.key) }
        }
        registry.element(BuiltinProjection.Stack.type, { integers(it, 1).also { fields -> entry(Alignment.entries, fields[0]) } }) { fields, context ->
            val alignment = entry(Alignment.entries, fields[0])
            evaluateComponentTree { Stack(context.modifier, context.key, alignment) { context.children.forEach(::element) } }
        }
        registry.element(BuiltinProjection.Row.type, {
            integers(it, 3).also { fields ->
                entry(Arrangement.entries, fields[1])
                entry(VerticalAlignment.entries, fields[2])
            }
        }) { fields, context ->
            val arrangement = entry(Arrangement.entries, fields[1])
            val alignment = entry(VerticalAlignment.entries, fields[2])
            evaluateComponentTree { Row(context.modifier, context.key, fields[0], arrangement, alignment) { context.children.forEach(::element) } }
        }
        registry.element(BuiltinProjection.Column.type, {
            integers(it, 3).also { fields ->
                entry(Arrangement.entries, fields[1])
                entry(HorizontalAlignment.entries, fields[2])
            }
        }) { fields, context ->
            val arrangement = entry(Arrangement.entries, fields[1])
            val alignment = entry(HorizontalAlignment.entries, fields[2])
            evaluateComponentTree { Column(context.modifier, context.key, fields[0], arrangement, alignment) { context.children.forEach(::element) } }
        }
        registry.element(BuiltinProjection.Grid.type, {
            integers(it, 4).also { fields ->
                require(0 < fields[0])
                entry(Alignment.entries, fields[3])
            }
        }) { fields, context ->
            val alignment = entry(Alignment.entries, fields[3])
            evaluateComponentTree { Grid(fields[0], context.modifier, context.key, fields[1], fields[2], alignment) { context.children.forEach(::element) } }
        }
        registry.element(BuiltinProjection.FlowRow.type, {
            integers(it, 4).also { fields ->
                entry(Arrangement.entries, fields[2])
                entry(VerticalAlignment.entries, fields[3])
            }
        }) { fields, context ->
            val arrangement = entry(Arrangement.entries, fields[2])
            val alignment = entry(VerticalAlignment.entries, fields[3])
            evaluateComponentTree { FlowRow(context.modifier, context.key, fields[0], fields[1], arrangement, alignment) { context.children.forEach(::element) } }
        }
        registry.element(BuiltinProjection.Observe.type, { integers(it, 0) }) { _, context -> RemoteRegionElement(context, false) }
        registry.element(BuiltinProjection.StateComponent.type, { integers(it, 0) }) { _, context -> RemoteRegionElement(context, true) }
    }

    private fun integers(
        value: ProjectionValue,
        count: Int,
    ): List<Int> {
        val fields = ProjectionFields(value)
        val result = List(count) { fields.int(0..Int.MAX_VALUE) }
        fields.finish()
        return result
    }

    private fun <T> entry(
        entries: List<T>,
        index: Int,
    ): T = requireNotNull(entries.getOrNull(index)) { "Invalid layout enum value." }
}
