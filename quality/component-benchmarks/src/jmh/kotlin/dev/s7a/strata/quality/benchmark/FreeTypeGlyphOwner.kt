package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Owns one genuine native face and immutable Text inputs; every dirty operation creates an independent host.
 * Its reset includes host/native creation and close so conversion misses cannot be confused with warm frames.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught") // Cleanup also owns assertion and linkage failures.
internal class FreeTypeGlyphOwner(
    private val fixture: FreeTypeGlyphFixture,
) : AutoCloseable {
    private var face: MinecraftTrueTypeFace? = null
    private val source = FreeTypeGrayscaleAssets.source(fixture.settings)
    private val snapshot = FreeTypeGrayscaleAssets.snapshot(source)
    private val profile = ComponentProfile.create(snapshot)
    private val text = String(Character.toChars(fixture.scalar))
    private val backend: MinecraftFontBackend = LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(MinecraftTrueTypeRasterizer.FreeType))

    init {
        try {
            val opened = backend.openTrueType(FreeTypeGrayscaleAssets.font(), fixture.settings)
            face = opened
            checkNotNull(opened.glyph(fixture.scalar)?.image) { "The frozen real glyph is absent, empty or rejected" }
        } catch (failure: Throwable) {
            try {
                backend.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * Genuine native provider path on the prepared face, without an engine raster cache.
     */
    internal fun glyph(): MinecraftFontGlyph = checkNotNull(checkNotNull(face).glyph(fixture.scalar))

    /**
     * Complete cold dirty/reset operation through the actual Minecraft profile, including terminal close.
     */
    internal fun dirtyText(): RuntimeUiFrame = host().use { owner ->
        owner.attach()
        owner.frame(viewport)
    }

    /**
     * Returns a primed host for the unchanged complete clean-frame control.
     */
    internal fun cleanHost(): MinecraftUiHost = host().also { owner ->
        try {
            owner.attach()
            owner.frame(viewport)
        } catch (failure: Throwable) {
            try {
                owner.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * Independent complete Text/profile output from scalar-oracle pixels and metrics, without native conversion.
     */
    internal fun referenceText(glyph: MinecraftFontGlyph): RuntimeUiFrame {
        val factory =
            MinecraftFontBackendFactory {
                object : MinecraftFontBackend by backend {
                    override fun openTrueType(
                        bytes: ByteArray,
                        settings: MinecraftTrueTypeSettings,
                    ): MinecraftTrueTypeFace =
                        object : MinecraftTrueTypeFace {
                            override fun glyph(codePoint: Int): MinecraftFontGlyph? = if (codePoint == fixture.scalar) glyph else null

                            override fun close() = Unit
                        }

                    override fun close() = Unit
                }
            }
        return host(factory).use { owner ->
            owner.attach()
            owner.frame(viewport)
        }
    }

    private fun host(factory: MinecraftFontBackendFactory = LwjglMinecraftFontBackendFactory): MinecraftUiHost =
        createMinecraftUiHost(UiDefinition("FreeType grayscale Text") { Text(text) }, profile, factory)

    /**
     * Closes the trial face and backend; returned images and frame commands retain detached pixels only.
     */
    override fun close() {
        face = null
        backend.close()
    }

    /**
     * Shared full-frame geometry, identical on both archive sides.
     */
    internal companion object {
        /**
         * Fixed logical viewport used for every real glyph/Text fixture and the clean control.
         */
        internal val viewport: IntSize = IntSize(320, 240)
    }
}
