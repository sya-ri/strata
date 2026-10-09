package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.InvocationTargetException

/**
 * Untimed probe invokes the genuine background node with the genuine private guarded core collector.
 * Reflection observes the actual collector and its integer-presentation spans; it does not substitute a tiler or a custom scope.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftTileProducerProbe {
    /**
     * Returns actual integer-presentation source/geometry triples after the genuine callback finishes.
     * Candidate callbacks store one descriptor; requesting its lazy presentation here materializes spans outside the callback.
     * These span counts are distinct from eager producer allocations and original virtual-grid membership.
     */
    fun collect(
        image: DrawImage,
        size: IntSize,
        scale: ImageScale,
    ): List<Triple<DrawImage, IntRect, IntRect>> {
        val ownerType = Class.forName("dev.s7a.strata.runtime.OwnerGuard")
        val ownerConstructor = ownerType.getDeclaredConstructor().apply { check(trySetAccessible()) }
        val scopeType = Class.forName("dev.s7a.strata.runtime.LocalPaintScope")
        val constructor = scopeType.getDeclaredConstructor(ownerType, IntSize::class.java).apply { check(trySetAccessible()) }
        val scope = constructor.newInstance(ownerConstructor.newInstance(), size) as PaintScope
        val snapshot = scopeType.getDeclaredMethod("snapshot").apply { check(trySetAccessible()) }
        val close = scopeType.getDeclaredMethod("close").apply { check(trySetAccessible()) }
        val element = createMinecraftImageBackgroundModifier(image, scale)
        element.type.validateErased(element)
        val node = element.type.createErased(element) as PaintNode
        try {
            node.paint(scope)
            val collected = checkNotNull(snapshot.invoke(scope) as? List<*>)
            return collected.flatMap { value ->
                val command = checkNotNull(value)
                val commands = command.javaClass.declaredMethods.singleOrNull { it.name.contentEquals("getCommands") }
                val blits = if (commands == null) listOf(command) else checkNotNull(commands.invoke(command) as? List<*>)
                blits.map { blit ->
                    val actual = checkNotNull(blit)
                    val getters = actual.javaClass.methods.associateBy { it.name }
                    Triple(
                        getters.getValue("getImage").invoke(actual) as DrawImage,
                        getters.getValue("getSource").invoke(actual) as IntRect,
                        getters.getValue("getDestination").invoke(actual) as IntRect,
                    )
                }
            }
        } catch (failure: InvocationTargetException) {
            throw (failure.cause ?: failure)
        } finally {
            close.invoke(scope)
        }
    }
}
