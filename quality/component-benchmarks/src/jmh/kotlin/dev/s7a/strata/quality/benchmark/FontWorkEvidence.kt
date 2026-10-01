package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph

/**
 * Untimed admission checks for real providers, cache ceilings, native face pressure and source graph limits.
 */
public object FontWorkEvidence {
    /**
     * Verifies generated registration and owner release without accepting timing as a correctness threshold.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        check(JmhWorkloadInventory.capture(listOf(FontProviderBenchmark::class.java, FontTextBenchmark::class.java), setOf("avgt")).size == 42)
        FontWorkload.entries.forEach { workload ->
            val owner = FontProviderBenchmark.FontSession().apply { this.workload = workload }
            owner.setup()
            try {
                val initial = owner.warm()
                repeat(3) { owner.churn() }
                val retained = owner.retained
                WorkExpectation(maximum = mapOf("RasterEntries" to owner.cacheEntries.toLong(), "RasterBytes" to 1024 * 1024L, "NativeFaces" to owner.faceLimit.toLong())).verify(mapOf("RasterEntries" to retained[0], "RasterBytes" to retained[1], "NativeFaces" to retained[2]))
                check(equalGlyph(initial, owner.warm())) { "Provider output changed under bounded cache pressure: $workload" }
                if (workload == FontWorkload.ReferenceDepth128) check(initial.advance == 7f && initial.image == null)
                if (workload in setOf(FontWorkload.StbFaces1, FontWorkload.FreeTypeFaces16)) check(retained[2] == owner.faceLimit.toLong())
                if (workload in setOf(FontWorkload.BitmapUncached, FontWorkload.UnihexUncached)) check(retained[0] == 0L && retained[1] == 0L)
                check(equalGlyph(owner.lifecycle(), owner.churn()))
                println("font=$workload,rasters=${retained[0]},bytes=${retained[1]},faces=${retained[2]}")
            } finally {
                owner.close()
            }
        }
        listOf(32, 16384).forEach { length ->
            val owner = FontTextBenchmark.TextSession().apply { this.length = length }
            owner.setup()
            try {
                val first = owner.visualGlyphs()
                check(first.isNotEmpty() && first == owner.visualGlyphs())
                println("bidi=$length,glyphs=${first.size}")
            } finally {
                owner.close()
            }
        }
    }

    private fun equalGlyph(
        first: MinecraftFontGlyph,
        second: MinecraftFontGlyph,
    ): Boolean =
        first.copy(image = null) == second.copy(image = null) && first.image?.size == second.image?.size &&
            (first.image?.copyArgb()?.contentEquals(second.image?.copyArgb()) ?: (second.image == null))
}
