package dev.s7a.strata.runtime.minecraft.font

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows

/**
 * Exact terminal ownership and cache-order observations for public-path callback tests.
 */
internal object FontTerminalTestSupport {
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
    fun rasterCodePoints(engine: MinecraftFontEngine): List<Int> =
        (field(engine, "rasters") as Map<*, *>).keys.map { value ->
            val key = checkNotNull(value)
            key.javaClass
                .getDeclaredField("codePoint")
                .apply { isAccessible = true }
                .getInt(key)
        }

    private fun field(
        engine: MinecraftFontEngine,
        name: String,
    ): Any? =
        MinecraftFontEngine::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(engine)
}
