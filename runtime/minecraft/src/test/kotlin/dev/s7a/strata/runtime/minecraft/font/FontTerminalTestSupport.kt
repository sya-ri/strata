package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import java.lang.reflect.InvocationTargetException

/**
 * Independent original provider walk and exact terminal-state observations for callback tests.
 * The walk borrows the engine's private preflight and raster hooks, with expected native calls and terminal results asserted separately.
 */
internal object FontTerminalTestSupport {
    /**
     * Chooses either the public walk or the original uncached-resolution provider loop on the same owner thread.
     */
    fun glyph(
        snapshot: MinecraftFontSnapshot,
        engine: MinecraftFontEngine,
        original: Boolean,
        scalar: Int = 'A'.code,
        font: ResourceId = FontTestResources.defaultFont,
    ): MinecraftFontGlyph {
        if (original.not()) return engine.glyph(font, scalar)
        FontJson.validateScalar(scalar)
        val selected = if (snapshot.compatibility.providerFilters.not() && snapshot.options.uniform && font == FontTestResources.defaultFont) ResourceId("minecraft", "uniform") else font
        val missing = engine.glyph(ResourceId("unknown", "reference"), scalar)
        val providers = snapshot.fonts[selected] ?: return missing
        if ((invoke(engine, "prepareFont", arrayOf(ResourceId::class.java, List::class.java), selected, providers) as Boolean).not()) return missing
        for (entry in providers) {
            val applies =
                entry.filter.all { (option, expected) ->
                    expected ==
                        when (option) {
                            FontOption.Uniform -> snapshot.options.uniform
                            FontOption.JapaneseVariants -> snapshot.options.japaneseVariants
                        }
                }
            if (applies) {
                val glyph = invoke(engine, "cachedGlyph", arrayOf(FontProviderEntry::class.java, Int::class.java), entry, scalar) as MinecraftFontGlyph?
                if (glyph != null) return glyph
            }
        }
        return missing
    }

    /**
     * Requires every resource-bearing map, byte counter and owner reference to remain empty after close and a late callback return.
     */
    fun assertTerminal(engine: MinecraftFontEngine) {
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(0, engine.retainedFaces)
        assertEquals(0L, field(engine, "faceBytes"))
        for (name in listOf("rasters", "faces", "bitmapSizes", "bitmapFailures", "faceFailures", "providerStatus", "fontStatus")) {
            assertEquals(0, (field(engine, name) as Map<*, *>).size, name)
        }
        assertEquals(0, (field(engine, "validatedFaces") as Set<*>).size)
        assertNull(field(engine, "snapshot"))
        assertNull(field(engine, "backend"))
        val rejected = assertThrows(IllegalStateException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
        assertEquals("Font engine is closed.", rejected.message)
        engine.close()
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0L, engine.retainedRasterBytes)
        assertEquals(0, engine.retainedFaces)
    }

    /**
     * Reads the current cache key order without invoking the access-ordered map.
     */
    fun rasterKeys(engine: MinecraftFontEngine): List<Any?> = (field(engine, "rasters") as Map<*, *>).keys.toList()

    private fun field(
        engine: MinecraftFontEngine,
        name: String,
    ): Any? = MinecraftFontEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    private fun invoke(
        engine: MinecraftFontEngine,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any,
    ): Any? =
        try {
            MinecraftFontEngine::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(engine, *args)
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.targetException)
        }
}
