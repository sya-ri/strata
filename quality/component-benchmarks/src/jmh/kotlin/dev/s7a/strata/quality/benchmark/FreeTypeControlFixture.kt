package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimitException
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Genuine no-opportunity and lifecycle work, with trial preparation excluded from every sampled boundary.
 * Snapshot replacement alternates two prepared immutable descriptors and closes the prior engine each time.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught") // Cleanup also owns assertion and linkage failures.
internal class FreeTypeControlFixture(
    private val control: FreeTypeGrayscaleBenchmark.Control,
) : AutoCloseable {
    private var backend: MinecraftFontBackend? = null
    private var face: MinecraftTrueTypeFace? = null
    private var provider: FontProviderBenchmark.FontSession? = null
    private var bitmap: FontRasterOwnershipBenchmark.BitmapSession? = null
    private var malformed: FreeTypeBitmapFixture? = null
    private var text: FreeTypeGlyphOwner? = null
    private var host: MinecraftUiHost? = null
    private var replacement: MinecraftFontEngine? = null
    private var snapshots = emptyList<MinecraftFontSnapshot>()
    private var revision = 0

    init {
        try {
            prepare()
        } catch (failure: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun prepare() {
        when (control) {
            FreeTypeGrayscaleBenchmark.Control.WarmFreeTypeRaster,
            FreeTypeGrayscaleBenchmark.Control.SnapshotLoad,
            FreeTypeGrayscaleBenchmark.Control.EngineLifecycle,
            -> {
                val owner = FontProviderBenchmark.FontSession().apply { workload = FontWorkload.FreeTypeCached }
                provider = owner
                owner.setup()
            }

            FreeTypeGrayscaleBenchmark.Control.BitmapGlyph -> {
                val owner = FontRasterOwnershipBenchmark.BitmapSession().apply { cell = 8 }
                bitmap = owner
                owner.setup()
            }

            FreeTypeGrayscaleBenchmark.Control.CompleteCleanTextFrame -> {
                val owner = FreeTypeGlyphOwner(FreeTypeGlyphFixture.SmallGlyphOne)
                text = owner
                host = owner.cleanHost()
            }

            FreeTypeGrayscaleBenchmark.Control.MalformedConverterInput -> malformed = FreeTypeBitmapFixture(8, FreeTypeGrayscaleBenchmark.Layout.PaddedNegative)
            FreeTypeGrayscaleBenchmark.Control.SnapshotReplacement -> {
                snapshots = listOf(11f, 12f).map { size -> FreeTypeGrayscaleAssets.snapshot(FreeTypeGrayscaleAssets.source(MinecraftTrueTypeSettings(size, 2f))) }
                replacement = engine(snapshots.first())
                checkNotNull(replacement).glyph(font, 65)
            }

            else -> prepareFace()
        }
    }

    private fun prepareFace() {
        val rasterizer = if (control == FreeTypeGrayscaleBenchmark.Control.StbGlyph) MinecraftTrueTypeRasterizer.Stb else MinecraftTrueTypeRasterizer.FreeType
        val owner = LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(rasterizer))
        backend = owner
        val settings = if (control == FreeTypeGrayscaleBenchmark.Control.AtlasRejectedGlyph) MinecraftTrueTypeSettings(512f) else FreeTypeGlyphFixture.SmallGlyphOne.settings
        face =
            if (control == FreeTypeGrayscaleBenchmark.Control.ImageLimitRejectedGlyph) {
                (owner as MinecraftBoundedFontBackend).openTrueType(FreeTypeGrayscaleAssets.font(), settings, MinecraftFontLoadLimits(maxImageDimension = 1, maxImageBytes = 4))
            } else {
                owner.openTrueType(FreeTypeGrayscaleAssets.font(), settings)
            }
    }

    /**
     * Returns one complete control result; absent native glyphs are represented by an unchanged Boolean result.
     */
    internal fun sample(): Any =
        when (control) {
            FreeTypeGrayscaleBenchmark.Control.WarmFreeTypeRaster -> checkNotNull(provider).warm()
            FreeTypeGrayscaleBenchmark.Control.MissingFreeTypeGlyph -> checkNotNull(face).glyph(0x2603) == null
            FreeTypeGrayscaleBenchmark.Control.EmptyFreeTypeGlyph -> checkNotNull(checkNotNull(face).glyph(0x20))
            FreeTypeGrayscaleBenchmark.Control.AtlasRejectedGlyph -> checkNotNull(checkNotNull(face).glyph(0x41))
            FreeTypeGrayscaleBenchmark.Control.ImageLimitRejectedGlyph -> rejectedImage()
            FreeTypeGrayscaleBenchmark.Control.StbGlyph -> checkNotNull(checkNotNull(face).glyph(0x41))
            FreeTypeGrayscaleBenchmark.Control.BitmapGlyph -> checkNotNull(bitmap).glyph()
            FreeTypeGrayscaleBenchmark.Control.SnapshotLoad -> checkNotNull(provider).load()
            FreeTypeGrayscaleBenchmark.Control.EngineLifecycle -> checkNotNull(provider).lifecycle()
            FreeTypeGrayscaleBenchmark.Control.SnapshotReplacement -> replace()
            FreeTypeGrayscaleBenchmark.Control.CompleteCleanTextFrame -> checkNotNull(host).frame(FreeTypeGlyphOwner.viewport)
            FreeTypeGrayscaleBenchmark.Control.MalformedConverterInput -> checkNotNull(malformed).malformed()
        }

    private fun rejectedImage(): MinecraftFontLoadLimitException =
        try {
            checkNotNull(face).glyph(0x41)
            error("Image-limit rejection was bypassed")
        } catch (failure: MinecraftFontLoadLimitException) {
            failure
        }

    private fun replace(): Any {
        checkNotNull(replacement).close()
        revision = 1 - revision
        val owner = engine(snapshots[revision])
        replacement = owner
        return owner.glyph(font, 65)
    }

    private fun engine(snapshot: MinecraftFontSnapshot): MinecraftFontEngine = MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory, cacheEntries = 64, cacheBytes = 1024 * 1024, maxFaces = 16)

    /**
     * Ends all independently owned resources, including replacement engines and the retained clean host.
     */
    override fun close() {
        var failure: Throwable? = null
        val releases = listOf({ host?.close() }, { text?.close() }, { replacement?.close() }, { malformed?.close() }, { bitmap?.close() }, { provider?.close() }, { backend?.close() })
        releases.forEach { release ->
            try {
                release()
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else if (primary !== caught) primary.addSuppressed(caught)
            }
        }
        host = null
        text = null
        replacement = null
        malformed = null
        bitmap = null
        provider = null
        face = null
        backend = null
        snapshots = emptyList()
        failure?.let { throw it }
    }

    private companion object {
        val font = ResourceId("minecraft", "default")
    }
}
