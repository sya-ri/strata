package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import java.util.Collections

/**
 * Owner-thread portable glyph engine for one immutable resource state.
 * The engine owns its backend and native faces, while returned glyphs retain only detached pixels and metrics.
 * Its access-ordered raster cache is bounded by both entry count and pixel bytes; large values bypass the cache.
 * Sheets share detached resource identity; bitmap scans share resource, grid, and cell identity with one current metric result per cell.
 * TrueType faces and glyphs share resource and exact settings, with provider skips checked before glyph lookup.
 * Other glyphs use snapshot-local provider identities and Unicode scalars; changing resources, limits, or options requires a new engine.
 * Decoded dimensions and detached failure messages are bounded by the current snapshot's resources and settings, even with raster caching disabled.
 * Native faces have an independent entry bound of at most 16 and a combined encoded-input ceiling equal to the snapshot's maxAssetBytes.
 * Eviction closes faces before opening replacements; successful descriptor checks survive eviction without retaining native state.
 * Preallocation-limit failures occupy ordinary bounded glyph-cache entries and retain only a message.
 * A backend returning an image beyond its allocation contract permanently disables that face descriptor and closes its live face.
 * Closing clears snapshot, cache, and native references and never invalidates returned glyphs.
 * Glyph selection, cache eviction, and terminal cleanup share this owner to preserve resource lifetime boundaries.
 *
 * @param snapshot immutable font definitions and resource bytes, safely reusable by other engines.
 * @param backendFactory creates the independently owned CPU backend.
 * @param cacheEntries maximum combined cached glyph or cell results and decoded bitmap sheets; zero disables raster caching.
 * @param cacheBytes maximum retained raster pixel bytes; zero disables pixel caching.
 * @param maxFaces maximum concurrently retained native faces, from 1 through 16.
 * @throws IllegalArgumentException when a raster cache bound is negative or the face bound is outside 1 through 16.
 * @throws Throwable when the backend cannot be opened.
 */
@Suppress("TooManyFunctions")
public class MinecraftFontEngine
    @JvmOverloads
    public constructor(
        snapshot: MinecraftFontSnapshot,
        backendFactory: MinecraftFontBackendFactory,
        private val cacheEntries: Int = 4096,
        private val cacheBytes: Long = 16L * 1024L * 1024L,
        private val maxFaces: Int = 16,
    ) : AutoCloseable {
        private val owner = Thread.currentThread()
        private var snapshot: MinecraftFontSnapshot? = snapshot
        private var backend: MinecraftFontBackend?
        private val rasters = LinkedHashMap<RasterKey, RasterValue>(16, 0.75f, true)
        private var rasterBytes = 0L
        private val faces = LinkedHashMap<FontFaceKey, MinecraftTrueTypeFace>(16, 0.75f, true)
        private var faceBytes = 0L
        private val validatedFaces = HashSet<FontFaceKey>()
        private val bitmapSizes = HashMap<FontResource, IntSize>()
        private val bitmapFailures = HashMap<FontResource, String>()
        private val faceFailures = HashMap<FontFaceKey, FaceFailure>()
        private val providerStatus = HashMap<Int, LoadStatus>()
        private val fontStatus = HashMap<ResourceId, LoadStatus>()
        private val loadDiagnostics = ArrayList(snapshot.diagnostics)
        private var closed = false

        init {
            require(0 <= cacheEntries && 0L <= cacheBytes && 0 < maxFaces && maxFaces <= 16) {
                "Font cache bounds must be non-negative and the face bound must be between 1 and 16."
            }
            backend = backendFactory.open(snapshot.compatibility)
        }

        /**
         * Immutable selection options captured by this still-open owner-thread engine.
         */
        public val options: MinecraftFontOptions
            get() = requireSnapshot().options

        /**
         * Immutable release capabilities captured by this still-open owner-thread engine.
         */
        public val compatibility: MinecraftFontCompatibility
            get() = requireSnapshot().compatibility

        /**
         * Number of cached glyph results and bitmap sheets; readable after owner-thread close.
         */
        public val retainedRasterEntries: Int
            get() {
                checkOwner()
                return rasters.size
            }

        /**
         * Total copied pixel payload retained by the cache, excluding caller-owned returned runs.
         */
        public val retainedRasterBytes: Long
            get() {
                checkOwner()
                return rasterBytes
            }

        /**
         * Number of currently retained native faces; zero after close.
         */
        public val retainedFaces: Int
            get() {
                checkOwner()
                return faces.size
            }

        /**
         * Detached resource and provider-load diagnostics accumulated by this owner-thread engine.
         */
        public val diagnostics: List<MinecraftFontDiagnostic>
            get() {
                checkOwner()
                return Collections.unmodifiableList(loadDiagnostics.toList())
            }

        /**
         * Resolves the first applicable provider for one Unicode scalar.
         * Every provider is preflighted before filtering, so a failed disabled provider still invalidates its bundle.
         * Unknown fonts and missing glyphs return the native missing-glyph shape instead of silently selecting the default font.
         * Atlas-rejected rasters use the missing shape with the selected release's advance behavior.
         * Successful providers retain native non-finite metrics without permitting them into portable drawing geometry.
         * This operation performs only derived work and never calls a tree or mutates application state.
         *
         * @param font structural font-family identifier.
         * @param codePoint Unicode scalar to resolve.
         * @return detached glyph metrics and pipeline-ready RGBA pixels.
         * @throws IllegalStateException when called from another thread or after close.
         * @throws IllegalArgumentException when [codePoint] is not a Unicode scalar.
         * @throws Throwable when a previously opened backend fails during rasterization.
         */
        public fun glyph(
            font: ResourceId,
            codePoint: Int,
        ): MinecraftFontGlyph {
            val current = requireSnapshot()
            FontJson.validateScalar(codePoint)
            val selected =
                if (current.compatibility.providerFilters.not() && current.options.uniform && font == defaultFont) uniformFont else font
            val providers = current.fonts[selected] ?: return missingGlyph
            if (prepareFont(selected, providers).not()) return missingGlyph
            for (entry in providers) {
                if (applies(entry.filter, current.options)) {
                    val glyph = cachedGlyph(entry, codePoint)
                    if (glyph != null) return glyph
                }
            }
            return missingGlyph
        }

        /**
         * Uses the selected backend's native-compatible text ordering without retaining the input string.
         *
         * @param text immutable logical text.
         * @param rightToLeft whether the fallback paragraph direction is right-to-left; defaults to the captured language option.
         * @return visual-order text.
         * @throws IllegalStateException when accessed from another thread or after close.
         */
        @JvmOverloads
        public fun visualOrder(
            text: String,
            rightToLeft: Boolean = options.rightToLeft,
        ): String {
            requireSnapshot()
            return checkNotNull(backend).visualOrder(text, rightToLeft)
        }

        /**
         * Shapes and orders a complete logical line while retaining original UTF-16 scalar positions for font inheritance.
         * Positions precede shaping and bidirectional reordering; contractions belong to their first contributing scalar.
         * No input or derived run is retained by the engine.
         *
         * @param text immutable logical text containing all spans on one line.
         * @param rightToLeft whether the fallback paragraph direction is right-to-left; defaults to the captured language option.
         * @return detached immutable visual glyphs whose source offsets belong to the original logical line.
         * @throws IllegalStateException when accessed from another thread or after close.
         */
        @JvmOverloads
        public fun visualGlyphs(
            text: String,
            rightToLeft: Boolean = options.rightToLeft,
        ): List<MinecraftVisualGlyph> {
            requireSnapshot()
            return Collections.unmodifiableList(checkNotNull(backend).visualGlyphs(text, rightToLeft).toList())
        }

        /**
         * Releases native faces and backend ownership exactly once on the owner thread.
         * Cleanup continues after failures; the first failure remains primary and distinct later failures are suppressed once.
         *
         * @throws IllegalStateException when called from another thread.
         * @throws Throwable when native cleanup fails.
         */
        override fun close() {
            checkOwner()
            if (closed) return
            closed = true
            snapshot = null
            rasters.clear()
            rasterBytes = 0
            providerStatus.clear()
            fontStatus.clear()
            bitmapSizes.clear()
            bitmapFailures.clear()
            faceFailures.clear()
            validatedFaces.clear()
            val retainedFaces = faces.values.toList()
            faces.clear()
            faceBytes = 0
            val retainedBackend = backend
            backend = null
            val failures = FontCloseFailures()
            retainedFaces.forEach { face -> failures.attempt(face::close) }
            if (retainedBackend != null) failures.attempt(retainedBackend::close)
            failures.throwFailure()
        }

        private fun prepareFont(
            font: ResourceId,
            providers: List<FontProviderEntry>,
        ): Boolean {
            fontStatus[font]?.let { return it === LoadStatus.Ready }
            var ready = true
            for (entry in providers) {
                val state =
                    providerStatus.getOrPut(entry.identity) {
                        runCatching { preflight(entry) }.fold(
                            onSuccess = { LoadStatus.Ready },
                            onFailure = { failure ->
                                if ((failure is Exception).not()) throw failure
                                loadDiagnostics +=
                                    MinecraftFontDiagnostic(
                                        MinecraftFontDiagnostic.Kind.ProviderLoadFailure,
                                        font,
                                        entry.source,
                                        failure.message ?: "Font provider failed during CPU loading.",
                                    )
                                LoadStatus.Failed
                            },
                        )
                    }
                if (state === LoadStatus.Failed) ready = false
            }
            fontStatus[font] = if (ready) LoadStatus.Ready else LoadStatus.Failed
            return ready
        }

        private fun preflight(entry: FontProviderEntry) {
            when (val provider = entry.provider) {
                is FontProvider.Bitmap -> {
                    val size = bitmapSizes[provider.resource] ?: bitmap(provider).size
                    require(0 < size.width / provider.columns && 0 < size.height / provider.rows) {
                        "Bitmap glyph cells must have positive dimensions."
                    }
                }

                is FontProvider.TrueType -> {
                    val key = FontFaceKey(provider.resource, provider.settings)
                    faceFailures[key]?.raise()
                    if (validatedFaces.contains(key).not()) face(provider)
                }

                is FontProvider.Space, is FontProvider.Unihex -> {}

                is FontProvider.Failed -> {
                    throw IllegalArgumentException(provider.diagnostic.message)
                }

                is FontProvider.Reference -> {
                    error("Unexpanded font reference reached the engine.")
                }
            }
        }

        private fun cachedGlyph(
            entry: FontProviderEntry,
            codePoint: Int,
        ): MinecraftFontGlyph? {
            val provider = entry.provider
            if (provider is FontProvider.Bitmap) return bitmapGlyph(provider, codePoint)
            if (provider is FontProvider.TrueType) faceFailures[FontFaceKey(provider.resource, provider.settings)]?.raise()
            if (provider is FontProvider.TrueType && codePoint in provider.skipped) return null
            val key =
                if (provider is FontProvider.TrueType) {
                    RasterKey.TrueTypeGlyph(FontFaceKey(provider.resource, provider.settings), codePoint)
                } else {
                    RasterKey.Glyph(entry.identity, codePoint)
                }
            val cached = rasters[key]
            if (cached is RasterValue.Glyph) return cached.value
            if (cached is RasterValue.GlyphFailure) throw MinecraftFontLoadLimitException(cached.message)
            val glyph =
                try {
                    resolveGlyph(provider, codePoint)?.let { resolved -> checkedGlyph(provider, resolved) }
                } catch (failure: MinecraftFontLoadLimitException) {
                    putRaster(key, RasterValue.GlyphFailure(failure.message ?: "Font glyph allocation limit exceeded."))
                    throw failure
                }
            putRaster(key, RasterValue.Glyph(glyph))
            return glyph
        }

        private fun checkedGlyph(
            provider: FontProvider,
            glyph: MinecraftFontGlyph,
        ): MinecraftFontGlyph {
            try {
                glyph.image?.let { image -> requireSnapshot().limits.requireImageSize(image.size.width, image.size.height) }
            } catch (failure: MinecraftFontLoadLimitException) {
                if (provider is FontProvider.TrueType) poisonFace(provider, failure)
                throw failure
            }
            return bakedGlyph(glyph)
        }

        private fun poisonFace(
            provider: FontProvider.TrueType,
            failure: MinecraftFontLoadLimitException,
        ) {
            val key = FontFaceKey(provider.resource, provider.settings)
            faceFailures[key] = FaceFailure(failure.message ?: "Font backend returned an oversized glyph image.", allocationLimit = true)
            validatedFaces.remove(key)
            val face = faces.remove(key) ?: return
            faceBytes -= provider.resource.size
            val cleanup = FontCloseFailures()
            cleanup.attempt { throw failure }
            cleanup.attempt(face::close)
            cleanup.throwFailure()
        }

        private fun resolveGlyph(
            provider: FontProvider,
            codePoint: Int,
        ): MinecraftFontGlyph? =
            when (provider) {
                is FontProvider.Bitmap -> error("Bitmap glyph lookup requires its shared cell cache.")
                is FontProvider.Space -> provider.advances[codePoint]?.let { advance -> spacingGlyph(advance) }
                is FontProvider.Unihex -> unihexGlyph(provider, codePoint)
                is FontProvider.TrueType -> face(provider).glyph(codePoint)
                is FontProvider.Failed, is FontProvider.Reference -> error("Unresolved provider reached glyph lookup.")
            }

        private fun bakedGlyph(glyph: MinecraftFontGlyph): MinecraftFontGlyph {
            val rasterSize = glyph.oversizedRasterSize ?: glyph.image?.size ?: return glyph
            if (rasterSize.width <= 256 && rasterSize.height <= 256) return glyph
            return if (requireSnapshot().compatibility.bakedGlyphMetrics) {
                missingGlyph
            } else {
                missingGlyph.copy(advance = glyph.advance, boldOffset = glyph.boldOffset, shadowOffset = glyph.shadowOffset)
            }
        }

        private fun bitmap(provider: FontProvider.Bitmap): DrawImage {
            val key = RasterKey.Bitmap(provider.resource)
            (rasters[key] as? RasterValue.Bitmap)?.let { return it.value }
            bitmapFailures[provider.resource]?.let { message -> throw IllegalArgumentException(message) }
            val image =
                runCatching { decodeBitmap(provider.resource) }.getOrElse { failure ->
                    if (failure is Exception) bitmapFailures[provider.resource] = failure.message ?: "Font bitmap decoding failed."
                    throw failure
                }
            bitmapSizes[provider.resource] = image.size
            putRaster(key, RasterValue.Bitmap(image))
            return image
        }

        private fun decodeBitmap(resource: FontResource): DrawImage {
            val decoder = checkNotNull(backend)
            val limits = requireSnapshot().limits
            val bitmapLimits = limits.copy(maxImageBytes = minOf(limits.maxImageBytes, limits.maxBitmapSheetBytes))
            val bytes = resource.copyBytes()
            val image = if (decoder is MinecraftBoundedFontBackend) decoder.decodePng(bytes, bitmapLimits) else decoder.decodePng(bytes)
            limits.requireBitmapSheetSize(image.size.width, image.size.height)
            return image
        }

        private fun bitmapGlyph(
            provider: FontProvider.Bitmap,
            codePoint: Int,
        ): MinecraftFontGlyph? {
            val cell = provider.cells[codePoint] ?: return null
            val key = RasterKey.BitmapCell(provider.resource, provider.columns, provider.rows, cell)
            val cached = rasters[key] as? RasterValue.BitmapCell
            if (cached != null && cached.height == provider.height && cached.ascent == provider.ascent) return cached.glyph
            val pixels = cached?.pixels ?: readBitmapCell(provider, cell)
            val glyph = bakedGlyph(bitmapMetrics(provider, pixels))
            putRaster(key, RasterValue.BitmapCell(pixels, provider.height, provider.ascent, glyph))
            return glyph
        }

        private fun readBitmapCell(
            provider: FontProvider.Bitmap,
            cell: Int,
        ): FontBitmapCell {
            val sheet = bitmap(provider)
            val width = sheet.size.width / provider.columns
            val height = sheet.size.height / provider.rows
            requireSnapshot().limits.requireImageSize(width, height)
            val originX = (cell % provider.columns) * width
            val originY = (cell / provider.columns) * height
            return FontBitmapCell.read(IntSize(width, height)) { x, y -> sheet.argbAt(originX + x, originY + y) }
        }

        private fun bitmapMetrics(
            provider: FontProvider.Bitmap,
            cellPixels: FontBitmapCell,
        ): MinecraftFontGlyph {
            val width = cellPixels.size.width
            val height = cellPixels.size.height
            val scale = provider.height.toFloat() / height
            val oversample = 1.0f / scale
            val advance = (0.5f + (cellPixels.rightmost + 1) * scale).toInt() + 1
            val originAdjustment = if (requireSnapshot().compatibility.rasterizer == MinecraftTrueTypeRasterizer.Stb) 3.0f else 0.0f
            val nativeTop = 7.0f + originAdjustment - provider.ascent
            // Earlier native glyphs subtract their render-origin adjustment only after adding the scaled bitmap height.
            val nativeBottom = nativeTop + height / oversample
            val right = width / oversample
            val top = nativeTop - originAdjustment
            val bottom = nativeBottom - originAdjustment
            return MinecraftFontGlyph(
                advance.toFloat(),
                minOf(0.0f, right),
                minOf(top, bottom),
                maxOf(0.0f, right),
                maxOf(top, bottom),
                cellPixels.image,
                orientation = if (provider.height < 0) SampledImageOrientation.FlipBoth else SampledImageOrientation.Normal,
                oversizedRasterSize = cellPixels.oversizedRasterSize,
            )
        }

        private fun unihexGlyph(
            provider: FontProvider.Unihex,
            codePoint: Int,
        ): MinecraftFontGlyph? {
            val glyph = provider.glyphs.glyph(codePoint) ?: return null
            val override = provider.overrides.firstOrNull { bounds -> codePoint in bounds.first..bounds.last }
            val bounds = override?.let { it.left..it.right } ?: glyph.bounds()
            val width = Math.addExact(Math.subtractExact(bounds.last, bounds.first), 1)
            requireSnapshot().limits.requireImageSize(width, 16)
            val pixels = IntArray(Math.multiplyExact(width, 16))
            for (y in 0 until 16) {
                for (x in 0 until width) {
                    val sourceX = bounds.first + x
                    if (glyph.ink(sourceX, y)) pixels[y * width + x] = -1
                }
            }
            val advance = if (requireSnapshot().compatibility.fractionalUnihexAdvance) width / 2.0f + 1.0f else (width / 2 + 1).toFloat()
            return MinecraftFontGlyph(
                advance,
                0.0f,
                0.0f,
                width / 2.0f,
                8.0f,
                createDrawImage(IntSize(width, 16), pixels),
                boldOffset = 0.5f,
                shadowOffset = 0.5f,
            )
        }

        private fun face(provider: FontProvider.TrueType): MinecraftTrueTypeFace {
            val key = FontFaceKey(provider.resource, provider.settings)
            faces[key]?.let { return it }
            faceFailures[key]?.raise()
            val weight = provider.resource.size.toLong()
            val maximumBytes = requireSnapshot().limits.maxAssetBytes.toLong()
            requireFontLimit(weight, maximumBytes, "retained native face input")
            while (faces.isNotEmpty() && (maxFaces <= faces.size || maximumBytes - weight < faceBytes)) {
                val oldest = faces.entries.iterator()
                val previous = oldest.next()
                oldest.remove()
                faceBytes -= previous.key.resource.size
                previous.value.close()
            }
            val face =
                runCatching {
                    val decoder = checkNotNull(backend)
                    val bytes = provider.resource.copyBytes()
                    if (decoder is MinecraftBoundedFontBackend) decoder.openTrueType(bytes, provider.settings, requireSnapshot().limits) else decoder.openTrueType(bytes, provider.settings)
                }.getOrElse { failure ->
                    if (failure is Exception) faceFailures[key] = FaceFailure(failure.message ?: "Font TrueType opening failed.", allocationLimit = false)
                    throw failure
                }
            faces[key] = face
            faceBytes += weight
            validatedFaces.add(key)
            return face
        }

        private fun putRaster(
            key: RasterKey,
            value: RasterValue,
        ) {
            val size = value.bytes()
            if (cacheEntries == 0 || cacheBytes < size) return
            rasters.remove(key)?.let { previous -> rasterBytes -= previous.bytes() }
            while (rasters.isNotEmpty() && (cacheEntries <= rasters.size || cacheBytes - size < rasterBytes)) {
                val oldest = rasters.entries.iterator()
                rasterBytes -= oldest.next().value.bytes()
                oldest.remove()
            }
            rasters[key] = value
            rasterBytes += size
        }

        private fun applies(
            filter: Map<FontOption, Boolean>,
            options: MinecraftFontOptions,
        ): Boolean =
            filter.all { (option, expected) ->
                expected ==
                    when (option) {
                        FontOption.Uniform -> options.uniform
                        FontOption.JapaneseVariants -> options.japaneseVariants
                    }
            }

        private fun requireSnapshot(): MinecraftFontSnapshot {
            checkOwner()
            check(closed.not()) { "Font engine is closed." }
            return checkNotNull(snapshot)
        }

        private fun checkOwner() {
            check(Thread.currentThread() === owner) { "Font engine requires its owner thread." }
        }

        private enum class LoadStatus {
            Ready,
            Failed,
        }

        private class FaceFailure(
            private val message: String,
            private val allocationLimit: Boolean,
        ) {
            fun raise(): Nothing =
                if (allocationLimit) {
                    throw MinecraftFontLoadLimitException(message)
                } else {
                    throw IllegalArgumentException(message)
                }
        }

        private sealed interface RasterKey {
            data class Glyph(
                val provider: Int,
                val codePoint: Int,
            ) : RasterKey

            data class Bitmap(
                val resource: FontResource,
            ) : RasterKey

            data class BitmapCell(
                val resource: FontResource,
                val columns: Int,
                val rows: Int,
                val cell: Int,
            ) : RasterKey

            data class TrueTypeGlyph(
                val face: FontFaceKey,
                val codePoint: Int,
            ) : RasterKey
        }

        private sealed interface RasterValue {
            fun bytes(): Long

            data class Glyph(
                val value: MinecraftFontGlyph?,
            ) : RasterValue {
                override fun bytes(): Long = value?.image?.let { image -> image.size.width.toLong() * image.size.height * 4L } ?: 0L
            }

            data class GlyphFailure(
                val message: String,
            ) : RasterValue {
                override fun bytes(): Long = 0L
            }

            data class Bitmap(
                val value: DrawImage,
            ) : RasterValue {
                override fun bytes(): Long = value.size.width.toLong() * value.size.height * 4L
            }

            data class BitmapCell(
                val pixels: FontBitmapCell,
                val height: Int,
                val ascent: Int,
                val glyph: MinecraftFontGlyph,
            ) : RasterValue {
                override fun bytes(): Long = glyph.image?.let { image -> image.size.width.toLong() * image.size.height * 4L } ?: 0L
            }
        }

        private companion object {
            val defaultFont: ResourceId = ResourceId("minecraft", "default")
            val uniformFont: ResourceId = ResourceId("minecraft", "uniform")
            val missingGlyph: MinecraftFontGlyph =
                MinecraftFontGlyph(
                    6.0f,
                    0.0f,
                    0.0f,
                    5.0f,
                    8.0f,
                    createDrawImage(IntSize(5, 8), IntArray(40) { index -> if (index % 5 in setOf(0, 4) || index / 5 in setOf(0, 7)) -1 else 0 }),
                )

            fun spacingGlyph(
                advance: Float,
                offset: Float = 1.0f,
            ): MinecraftFontGlyph = MinecraftFontGlyph(advance, 0.0f, 0.0f, 0.0f, 0.0f, null, boldOffset = offset, shadowOffset = offset)
        }
    }
