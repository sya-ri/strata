package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.input.TextInputEvent
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bounded untimed inspection of the actual host's current retained runtime graph.
 * Traversal stops at API values, application callbacks, fonts/assets and non-Strata implementation owners.
 * It detects event retention and historical preedit wrappers without adding runtime instrumentation or caches.
 */
internal object PreeditRetention {
    /**
     * Checks exact current editor/preedit ownership and rejects any raw event retained by the host graph.
     */
    internal fun inspect(
        host: Any,
        editors: Int,
        compositions: Int,
    ): List<Any> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>()
        val found = ArrayList<Any>()
        val editorType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextAreaEditor")
        val compositionType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextAreaPreedit")
        pending.add(host)
        var preedits = 0
        while (pending.isNotEmpty()) {
            val value = pending.removeFirst()
            if (visited.add(value).not()) continue
            check(visited.size <= 65_536) { "Retained runtime graph exceeded the declared inspection bound" }
            check((value is TextInputEvent.Preedit).not()) { "Host retained a raw input event" }
            if (editorType.isInstance(value)) found.add(value)
            if (compositionType.isInstance(value)) preedits++
            when (value) {
                is Array<*> -> value.filterNotNull().forEach(pending::add)
                is Iterable<*> -> value.filterNotNull().forEach(pending::add)
                is Map<*, *> -> (value.keys + value.values).filterNotNull().forEach(pending::add)
                else -> if (value.javaClass.name.startsWith("dev.s7a.strata.runtime.") && value.javaClass.name.contains(".font.").not()) enqueueFields(value, pending)
            }
        }
        check(found.size == editors && preedits == compositions) { "Current editor/composition ownership changed: ${found.size}/$preedits" }
        return found
    }

    private fun enqueueFields(
        value: Any,
        pending: ArrayDeque<Any>,
    ) {
        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.filter { Modifier.isStatic(it.modifiers).not() && it.type.isPrimitive.not() }.forEach {
                check(it.trySetAccessible())
                it.get(value)?.let(pending::add)
            }
            type = type.superclass
        }
    }
}
