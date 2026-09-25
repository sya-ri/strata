package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.server.packs.resources.ResourceManager
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * Observes Strata-owned private cache and immutable payload fields without depending on mapped Minecraft field names.
 *
 * Every operation belongs to the loaded client thread and returns only detached measurements or borrowed immutable values.
 * Native resource managers are never inspected reflectively, and no observation is an input to font rendering.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftProfileCacheInspection {
    /**
     * Returns the adapter's sole current profile or null while empty; no historical entries may be reachable.
     */
    fun profile(): MinecraftUiProfile? {
        val cache = cache()
        val current = field(cache, "current") as AtomicReference<*>
        val state = checkNotNull(current.get())
        val entry = field(state, "entry") ?: return null
        return field(entry, "value") as MinecraftUiProfile?
    }

    /**
     * Borrows the profile's detached font snapshot for identity, payload, and weak-reference assertions.
     */
    fun fonts(profile: MinecraftUiProfile): MinecraftFontSnapshot = field(profile, "fonts") as MinecraftFontSnapshot

    /**
     * Seeds the real cache with a detached profile under a test-owned native manager before exercising its actual lifecycle.
     *
     * The existing extraction implementation is bypassed only for this native-hook isolation probe, never for measured screen opens.
     * The callback is borrowed synchronously, and the test must close [manager] after its assertions.
     */
    fun seed(
        manager: ResourceManager,
        profile: MinecraftUiProfile,
    ) {
        val cache = cache()
        val fonts = fonts(profile)
        val method =
            cache.javaClass
                .getDeclaredMethod(
                    "get",
                    Any::class.java,
                    MinecraftFontCompatibility::class.java,
                    MinecraftFontOptions::class.java,
                    Supplier::class.java,
                ).apply { isAccessible = true }
        check(method.invoke(cache, manager, fonts.compatibility, fonts.options, Supplier { profile }) === profile)
    }

    /**
     * Counts unique reachable primitive-array payload bytes, excluding JVM object headers and temporary decode storage.
     *
     * The traversal reads only immutable Strata data and collection contents and does not retain the visited graph after return.
     */
    fun primitivePayloadBytes(snapshot: MinecraftFontSnapshot): Long {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>()
        pending.add(snapshot)
        var bytes = 0L
        while (pending.isNotEmpty()) {
            val value = pending.removeLast()
            if (visited.add(value).not()) continue
            bytes += appendChildren(value, pending)
        }
        return bytes
    }

    private fun appendChildren(
        value: Any,
        pending: ArrayDeque<Any>,
    ): Long =
        when (value) {
            is ByteArray -> {
                value.size.toLong()
            }

            is IntArray -> {
                value.size.toLong() * Int.SIZE_BYTES
            }

            is LongArray -> {
                value.size.toLong() * Long.SIZE_BYTES
            }

            is Map<*, *> -> {
                value.forEach { (key, item) ->
                    key?.let(pending::add)
                    item?.let(pending::add)
                }
                0L
            }

            is Iterable<*> -> {
                value.filterNotNull().forEach(pending::add)
                0L
            }

            is Array<*> -> {
                value.filterNotNull().forEach(pending::add)
                0L
            }

            else -> {
                appendStrataFields(value, pending)
                0L
            }
        }

    private fun appendStrataFields(
        value: Any,
        pending: ArrayDeque<Any>,
    ) {
        if (value.javaClass.packageName
                .startsWith("dev.s7a.strata.")
                .not() || value is Enum<*>
        ) {
            return
        }
        value.javaClass.declaredFields.filter { Modifier.isStatic(it.modifiers).not() }.forEach { member ->
            member.isAccessible = true
            member.get(value)?.let(pending::add)
        }
    }

    private fun cache(): Any =
        checkNotNull(
            Class
                .forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftProfileLifecycleKt")
                .getDeclaredField("currentProfile")
                .apply { isAccessible = true }
                .get(null),
        )

    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)
}
