@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Explicit client-installed Canvas renderer schemas and server-only references to their typed properties.
 * Native source acquisition and GPU work follow the client's existing Canvas attachment and presenter contracts.
 */
public object RemoteCanvas {
    /**
     * Describes a server Canvas rendered by an already installed client extension.
     * The immutable property value and encoder remain server-owned; only encoded properties and the destination size transfer.
     * Rendering this source in a local host is unsupported and fails explicitly.
     */
    public fun <P : Any> source(
        type: ProjectionType,
        properties: P,
        encode: (P) -> ProjectionValue,
    ): CanvasSource =
        CanvasSource {
            object : CanvasBinding {
                private var current: P? = properties

                override fun project(destinationSize: IntSize): DeclarationProjection<*> =
                    DeclarationProjection(type, checkNotNull(current) { "Remote Canvas binding is closed." }) { value, _ ->
                        RemoteProperties.record(RemoteProperties.encode(destinationSize), encode(value))
                    }

                override fun paint(scope: PaintScope): Unit = throw UnsupportedOperationException("Remote Canvas sources require their registered client renderer.")

                override fun close() {
                    current = null
                }
            }
        }

    /**
     * Registers a trusted client source factory before negotiation.
     * Decoding and source description creation must acquire no attachment resources; Canvas opens and closes each source binding.
     */
    public fun <P : Any> register(
        registry: RemoteRegistry,
        type: ProjectionType,
        decode: (ProjectionValue) -> P,
        create: (P) -> CanvasSource,
    ) {
        registry.element(type, { value ->
            val fields = ProjectionFields(value)
            val size = RemoteProperties.size(fields.value())
            require(0 < size.width && 0 < size.height)
            val properties = decode(fields.value())
            fields.finish()
            val source = create(properties)
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                evaluateComponentTree { Canvas(source, size, context.modifier, context.key) }
            }
            factory
        }) { factory, context -> factory(context) }
    }

    /**
     * Installs the built-in CPU image schema with generation-bearing source snapshots.
     */
    internal fun registerPixels(registry: RemoteRegistry) {
        registry.element(BuiltinProjection.CanvasPixels.type, { value ->
            val fields = ProjectionFields(value)
            val size = IntSize(fields.int(1..Int.MAX_VALUE), fields.int(1..Int.MAX_VALUE))
            require(0 <= fields.long())
            val image = requireNotNull(RemoteImageCodec().decode(fields.value()) as? ImageSource.Pixels).image
            fields.finish()
            val source = canvasSource(image)
            val factory: (RemoteElementContext) -> Element = { context ->
                require(context.children.isEmpty())
                evaluateComponentTree { Canvas(source, size, context.modifier, context.key) }
            }
            factory
        }) { factory, context -> factory(context) }
    }
}
