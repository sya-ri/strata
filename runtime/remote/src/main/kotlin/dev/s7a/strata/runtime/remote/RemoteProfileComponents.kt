@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.TabSelectionIndicator
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteProperties.enumeration
import dev.s7a.strata.runtime.remote.RemoteProperties.optional
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Client reconstruction of portable profile properties through the currently installed component runtime.
 * Decoding precedes retained mutation; actual resource lookup follows ordinary local-screen ownership.
 */
internal object RemoteProfileComponents {
    /**
     * Registers the standard display primitives and profile modifiers.
     */
    fun register(registry: RemoteRegistry) {
        textAndActions(registry)
        display(registry)
        backgrounds(registry)
    }

    private fun textAndActions(registry: RemoteRegistry) {
        element(registry, RemoteProfileComponent.Text) {
            val text = RemoteTextCodec.decode(value())
            val layout = RemoteProperties.textLayout(value())
            val style = enumeration<TextStyle>(this)
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                ComponentRuntimeBridge.current().text(text, layout, style, context.modifier, context.key)
            }
            factory
        }
        element(registry, RemoteProfileComponent.Button) {
            val label = RemoteTextCodec.decode(value())
            val width = int(1..Int.MAX_VALUE)
            val enabled = flag()
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                ComponentRuntimeBridge.current().button(label, width, enabled, context.modifier, context.key)
            }
            factory
        }
        element(registry, RemoteProfileComponent.Tab) {
            val label = RemoteTextCodec.decode(value())
            val selected = flag()
            val width = int(1..Int.MAX_VALUE)
            val enabled = flag()
            val customIndicator = flag()
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.size <= 1)
                val indicator = if (customIndicator) TabSelectionIndicator.Custom { context.children.forEach(::element) } else TabSelectionIndicator.Underline
                ComponentRuntimeBridge.current().tab(label, selected, width, enabled, indicator, context.children.singleOrNull(), context.modifier, context.key)
            }
            factory
        }
    }

    private fun display(registry: RemoteRegistry) {
        element(registry, RemoteProfileComponent.ProgressBar) {
            val progress = real().also { require(it in 0.0..1.0) }
            val size = RemoteProperties.size(value())
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                ComponentRuntimeBridge.current().progressBar(progress, size, context.modifier, context.key)
            }
            factory
        }
        element(registry, RemoteProfileComponent.LoadingIndicator) {
            val size = RemoteProperties.size(value())
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                ComponentRuntimeBridge.current().loadingIndicator(size, context.modifier, context.key)
            }
            factory
        }
        element(registry, RemoteProfileComponent.Image) {
            val source = RemoteProperties.image(value())
            val sourceRegion = optional(value(), RemoteProperties::rectangle)
            val size = optional(value(), RemoteProperties::size)
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                ComponentRuntimeBridge.current().image(source, sourceRegion, size, context.modifier, context.key)
            }
            factory
        }
        element(registry, RemoteProfileComponent.Slot) {
            val binding = optional(value(), RemoteProperties::slot)
            val highlightable = flag()
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.size <= 1)
                ComponentRuntimeBridge.current().slot(binding, highlightable, context.children.singleOrNull(), context.modifier, context.key)
            }
            factory
        }
        element(registry, RemoteProfileComponent.PlayerHead) {
            val source = RemoteProperties.skin(value())
            val size = int(1..Int.MAX_VALUE)
            val showHat = flag()
            val loading = flag()
            val failure = flag()
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.size == (if (loading) 1 else 0) + (if (failure) 1 else 0))
                ComponentRuntimeBridge.current().playerHead(source, size, showHat, if (loading) context.children.first() else null, if (failure) context.children.last() else null, context.modifier, context.key)
            }
            factory
        }
    }

    private fun backgrounds(registry: RemoteRegistry) {
        modifier(registry, RemoteProfileComponent.Tooltip) {
            val text = RemoteTextCodec.decode(value())
            val delay = long().also { require(0 <= it) }
            val factory = { ComponentRuntimeBridge.current().tooltip(Modifier.Empty, text, delay) }
            factory
        }
        modifier(registry, RemoteProfileComponent.MenuBackground) {
            val factory = { ComponentRuntimeBridge.current().menuBackground(Modifier.Empty) }
            factory
        }
        modifier(registry, RemoteProfileComponent.ContainerBackground) {
            val rows = int(1..6)
            val factory = { ComponentRuntimeBridge.current().containerBackground(Modifier.Empty, rows) }
            factory
        }
        modifier(registry, RemoteProfileComponent.ImageBackground) {
            val source = RemoteProperties.image(value())
            val scale = enumeration<ImageScale>(this)
            val factory = { ComponentRuntimeBridge.current().imageBackground(Modifier.Empty, source, scale) }
            factory
        }
        modifier(registry, RemoteProfileComponent.NineSliceBackground) {
            val source = RemoteProperties.image(value())
            val border = RemoteProperties.insets(value())
            val mode = if (flag()) NineSliceCenterMode.Stretched else NineSliceCenterMode.Tiled
            val factory = { ComponentRuntimeBridge.current().imageBackground(Modifier.Empty, source, border, mode) }
            factory
        }
    }

    private fun element(
        registry: RemoteRegistry,
        kind: RemoteProfileComponent,
        decode: ProjectionFields.() -> ((RemoteElementContext) -> Element),
    ) {
        registry.element(kind.type, { value -> fields(value, decode) }) { factory, context -> factory(context) }
    }

    private fun modifier(
        registry: RemoteRegistry,
        kind: RemoteProfileComponent,
        decode: ProjectionFields.() -> (() -> Modifier),
    ) {
        registry.modifier(kind.type, { value -> fields(value, decode) }) { factory, _ -> factory() }
    }

    private fun <T> fields(
        value: ProjectionValue,
        decode: ProjectionFields.() -> T,
    ): T {
        val fields = ProjectionFields(value)
        val result = fields.decode()
        fields.finish()
        return result
    }
}
