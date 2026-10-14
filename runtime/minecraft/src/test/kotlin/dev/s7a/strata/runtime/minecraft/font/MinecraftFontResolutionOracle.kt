package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import java.lang.reflect.InvocationTargetException

/**
 * Keeps the independent original provider walk and retention observations shared by portable resolution controls.
 */
internal object MinecraftFontResolutionOracle {
    /**
     * Compares the complete glyph, raster access order, retention and diagnostics with the independent original loop.
     */
    internal fun assertEquivalent(
        snapshot: MinecraftFontSnapshot,
        reference: MinecraftFontEngine,
        candidate: MinecraftFontEngine,
        font: ResourceId,
        scalar: Int,
    ) {
        assertEquals(originalGlyph(snapshot, reference, font, scalar), candidate.glyph(font, scalar))
        assertEquals(rasterKeys(reference), rasterKeys(candidate))
        assertEquals(reference.retainedRasterBytes, candidate.retainedRasterBytes)
        assertEquals(reference.retainedFaces, candidate.retainedFaces)
        assertEquals(reference.diagnostics, candidate.diagnostics)
    }

    /**
     * Runs the original provider loop through the existing engine preparation and raster boundaries, preserving the actual target failure.
     */
    internal fun originalGlyph(
        snapshot: MinecraftFontSnapshot,
        engine: MinecraftFontEngine,
        font: ResourceId,
        scalar: Int,
    ): MinecraftFontGlyph {
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
     * Reads the current raster LRU order without touching the cache.
     */
    internal fun rasterKeys(engine: MinecraftFontEngine): List<Any?> =
        (
            MinecraftFontEngine::class.java
                .getDeclaredField("rasters")
                .apply { isAccessible = true }
                .get(engine) as Map<*, *>
        ).keys
            .toList()

    /**
     * Reads the candidate's retained selection-entry count without performing resolution.
     */
    internal fun resolutionEntries(engine: MinecraftFontEngine): Int =
        (
            MinecraftFontEngine::class.java
                .getDeclaredField("resolutions")
                .apply { isAccessible = true }
                .get(engine) as Map<*, *>
        ).size

    /**
     * Reads the candidate's actual weighted selection units without performing resolution.
     */
    internal fun resolutionUnits(engine: MinecraftFontEngine): Int =
        MinecraftFontEngine::class.java
            .getDeclaredField("resolutionUnits")
            .apply { isAccessible = true }
            .getInt(engine)

    /**
     * Builds an owner-local fake face that records each real engine raster request.
     */
    internal fun observingBackend(
        width: Int,
        onRaster: () -> Unit,
    ): FontTestBackend =
        FontTestBackend(
            open = { _, _ ->
                FontTestFace(
                    lookup = {
                        onRaster()
                        raster(width)
                    },
                )
            },
        )

    /**
     * Invokes the supplied engine callback once from the actual face lookup boundary.
     */
    internal fun reenteringBackend(onFirstGlyph: (Int) -> Unit): FontTestBackend {
        var reentered = false
        return FontTestBackend(
            open = { _, _ ->
                FontTestFace(
                    lookup = { scalar ->
                        if (reentered.not()) {
                            reentered = true
                            onFirstGlyph(scalar)
                        }
                        raster(1)
                    },
                )
            },
        )
    }

    /**
     * Creates the deterministic glyph used by the independent portable face controls.
     */
    internal fun raster(width: Int): MinecraftFontGlyph = MinecraftFontGlyph(7f, 0f, 0f, width.toFloat(), 1f, createDrawImage(IntSize(width, 1), IntArray(width) { -1 }))

    // Reflection preserves the exact target throwable rather than wrapping the independent provider loop.
    @Suppress("SwallowedException")
    private fun invoke(
        engine: MinecraftFontEngine,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any,
    ): Any? =
        try {
            MinecraftFontEngine::class.java
                .getDeclaredMethod(name, *types)
                .apply { isAccessible = true }
                .invoke(engine, *args)
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.targetException)
        }
}
