package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import java.util.IdentityHashMap
import java.lang.reflect.Modifier as ReflectionModifier

/**
 * Owner-thread inspection of actual painter ownership, bounded to the two production runtime code origins.
 * External application state, fixtures, platforms and API image storage are traversal boundaries.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object PlayerHeadCacheProbe {
    /**
     * One detached observation of the current retained key and independently prepared layers.
     */
    internal data class Snapshot(
        internal val skin: DrawImage?,
        internal val face: DrawImage?,
        internal val hat: DrawImage?,
    )

    /**
     * Finds current painters without following borrowed authority or retaining graph history.
     */
    internal fun painters(host: MinecraftUiHost): List<MinecraftPlayerHeadPainter> {
        val origins = setOf(host.javaClass.protectionDomain.codeSource.location, UiTree::class.java.protectionDomain.codeSource.location)
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>()
        val result = ArrayList<MinecraftPlayerHeadPainter>()
        pending.add(host)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (visited.add(current).not()) continue
            when {
                current is MinecraftPlayerHeadPainter -> result.add(current)
                current is MinecraftUiPlatform -> Unit
                current is Collection<*> -> current.filterNotNull().forEach(pending::add)
                current is Map<*, *> -> {
                    current.keys.filterNotNull().forEach(pending::add)
                    current.values.filterNotNull().forEach(pending::add)
                }
                current is Array<*> -> current.filterNotNull().forEach(pending::add)
                current.javaClass.protectionDomain.codeSource?.location in origins -> {
                    var type: Class<*>? = current.javaClass
                    while (type != null && type != Any::class.java) {
                        type.declaredFields.filter { ReflectionModifier.isStatic(it.modifiers).not() }.forEach { field ->
                            field.isAccessible = true
                            field.get(current)?.let(pending::add)
                        }
                        type = type.superclass
                    }
                }
            }
        }
        return result
    }

    /**
     * Reads only the existing private source/face/hat fields; tests never mutate runtime state.
     */
    internal fun snapshot(painter: MinecraftPlayerHeadPainter): Snapshot =
        Snapshot(read(painter, Slot.Skin), read(painter, Slot.Face), read(painter, Slot.Hat))

    private fun read(
        painter: MinecraftPlayerHeadPainter,
        slot: Slot,
    ): DrawImage? {
        val field = MinecraftPlayerHeadPainter::class.java.getDeclaredField(slot.field)
        field.isAccessible = true
        return field.get(painter) as? DrawImage
    }

    private enum class Slot(
        val field: String,
    ) {
        Skin("cachedSkin"),
        Face("cachedFace"),
        Hat("cachedHat"),
    }
}
