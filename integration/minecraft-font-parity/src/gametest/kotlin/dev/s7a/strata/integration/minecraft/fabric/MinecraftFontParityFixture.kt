package dev.s7a.strata.integration.minecraft.fabric

import com.ibm.icu.util.VersionInfo
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.Version
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Original pack inputs and shared assertions for the independent native font oracle.
 * Fixtures are immutable resource bytes; native providers and the portable engine load them independently.
 * Host and engine work stays on the calling client thread and closes all owned backends before returning.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftFontParityFixture {
    /**
     * Logical viewport large enough for Minecraft to retain actual GUI scales one, two, and three.
     */
    val viewport: IntSize = IntSize(320, 240)

    /**
     * Opaque background exposes color and coverage blending independently of framebuffer transparency.
     */
    val background: Int = 0xFF285FAA.toInt()

    /**
     * Shared left origin of every native and portable text row.
     */
    const val LEFT: Int = 8

    /**
     * Typed native target, decoded on first native use so offline callers can supply their own compatibility contract.
     */
    val target: Target by lazy { Target.parse(System.getProperty("strata.minecraftVersion")) }

    /**
     * Ordered render cases include missing-glyph and unknown-font fallback without using native pixels as input.
     */
    val rows: List<Row> =
        FontCase.entries
            .flatMap { font ->
                listOf(TextStyle.Normal, TextStyle.Inactive, TextStyle.ContainerLabel).map { style -> font to style }
            }.mapIndexed { index, (font, style) ->
                Row(font.id, " A日한🙂A لا אב EΩ", style, 8 + index * 12)
            } + Row(ResourceId("strata_font_test", "unknown"), "A日한🙂", TextStyle.Normal, 224)

    /**
     * Reads one packaged fixture file, failing immediately when build resource wiring is incomplete.
     */
    fun bytes(path: String): ByteArray =
        checkNotNull(MinecraftFontParityFixture::class.java.classLoader.getResourceAsStream(path)) {
            "Missing font parity fixture: $path"
        }.use { it.readBytes() }

    /**
     * Loads detached original pack bytes with an explicit offline contract or the native runner's selected target.
     */
    fun snapshot(compatibility: MinecraftFontCompatibility = target.compatibility): MinecraftFontSnapshot =
        MinecraftFontSnapshot
            .load(
                listOf(MinecraftMemoryFontAssetSource("Original font parity fixtures", resourcePaths().associateWith(::bytes))),
                compatibility,
            ).also { snapshot -> check(snapshot.diagnostics.isEmpty()) { snapshot.diagnostics.joinToString() } }

    /**
     * Returns runtime, compatibility, scene, and original resource hashes shared by native and offline evidence.
     * These values describe the inputs and process; they do not assert native equality.
     */
    fun evidenceMetadata(compatibility: MinecraftFontCompatibility): Map<String, String> =
        linkedMapOf(
            "scene.sha256" to
                sha256(
                    buildString {
                        appendLine("${viewport.width},${viewport.height},$background,$LEFT")
                        rows.forEach { row -> appendLine("${row.font},${row.style},${row.top},${row.text}") }
                    }.toByteArray(),
                ),
            "compatibility.rasterizer" to compatibility.rasterizer.name,
            "compatibility.packFormat" to compatibility.packFormat.toString(),
            "compatibility.providerFilters" to compatibility.providerFilters.toString(),
            "compatibility.packOverlays" to compatibility.packOverlays.toString(),
            "compatibility.packFormatMinor" to compatibility.packFormatMinor.toString(),
            "compatibility.minorPackFormats" to compatibility.minorPackFormats.toString(),
            "compatibility.interleavedShadows" to compatibility.interleavedShadows.toString(),
            "compatibility.fractionalUnihexAdvance" to compatibility.fractionalUnihexAdvance.toString(),
            "compatibility.rejectMalformedOverlayMetadata" to compatibility.rejectMalformedOverlayMetadata.toString(),
            "compatibility.bakedGlyphMetrics" to compatibility.bakedGlyphMetrics.toString(),
            "compatibility.saturatingCeil" to compatibility.saturatingCeil.toString(),
            "compatibility.preparedTextBounds" to compatibility.preparedTextBounds.toString(),
            "runtime.lwjgl" to
                Version
                    .getVersion()
                    .substringBefore('+')
                    .substringBefore('-')
                    .substringBefore(' '),
            "runtime.lwjglDetail" to Version.getVersion(),
            "runtime.icu" to VersionInfo.ICU_VERSION.toString(),
            "runtime.javaFeature" to Runtime.version().feature().toString(),
            "runtime.javaVersion" to Runtime.version().toString(),
            "runtime.osName" to System.getProperty("os.name"),
            "runtime.osArch" to System.getProperty("os.arch"),
        ).apply {
            resourcePaths().forEach { path -> put("input.$path.sha256", sha256(bytes(path))) }
        }

    /**
     * Returns the detached bytes' SHA-256 digest for artifact integrity checks without altering them.
     */
    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Creates a complete synthetic GUI profile whose only exercised resources are the independent font snapshot.
     */
    fun profile(snapshot: MinecraftFontSnapshot): MinecraftUiProfile =
        createMinecraftUiProfile {
            fonts(snapshot)
            menuBackground(clear(16, 16))
            containerBackground(clear(256, 256))
            slotHighlightBack(clear(24, 24))
            slotHighlightFront(clear(24, 24))
            listBackground(clear(16, 16))
            listHeaderSeparator(clear(32, 2))
            listFooterSeparator(clear(32, 2))
            scrollbarBackground(clear(6, 32))
            scrollbarThumb(clear(6, 32))
            checkbox(clear(20, 20))
            checkboxHighlighted(clear(20, 20))
            checkboxSelected(clear(20, 20))
            checkboxSelectedHighlighted(clear(20, 20))
            slider(clear(200, 20))
            sliderHighlighted(clear(200, 20))
            sliderHandle(clear(8, 20))
            sliderHandleHighlighted(clear(8, 20))
            loadingIndicator(clear(5, 6))
            progressBarBorder(clear(12, 12))
            progressBarFill(clear(6, 6))
            progressBarFull(clear(6, 6))
            tooltipBackground(clear(100, 100))
            tooltipFrame(clear(100, 100))
            textFieldNormal(clear(200, 20))
            textFieldHighlighted(clear(200, 20))
            buttonNormal(clear(200, 20), 1, NineSliceCenterMode.Tiled)
            buttonHighlighted(clear(200, 20), 1, NineSliceCenterMode.Tiled)
            buttonDisabled(clear(200, 20), 1, NineSliceCenterMode.Tiled)
        }

    /**
     * Returns a fresh one-shot public Text component scene; no native measurement or raster is reused.
     */
    fun definition(): ScreenDefinition =
        ScreenDefinition("Independent font parity") {
            Stack(modifier = Modifier.Empty.fillMaxSize().background(ArgbColor(background))) {
                rows.forEach { row ->
                    Text(
                        row.text,
                        row.font,
                        row.style,
                        modifier = Modifier.Empty.padding(left = LEFT, top = row.top),
                    )
                }
            }
        }

    /**
     * Renders actual public Text components through the portable host at the requested final physical density.
     */
    fun render(
        profile: MinecraftUiProfile,
        scale: Int,
    ): HeadlessImage =
        createMinecraftUiHost(definition(), profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val frame = host.frame(viewport)
            rasterizeHeadless(frame.drawCommands, frame.size, scale)
        }

    /**
     * Returns detached commands from a fresh portable host for independent GPU-difference arithmetic.
     * Closing the host releases its backend; immutable command images remain valid and contain only original-resource-derived pixels.
     */
    fun commands(profile: MinecraftUiProfile): List<DrawCommand> =
        createMinecraftUiHost(definition(), profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(viewport).drawCommands.toList()
        }

    /**
     * Returns every immutable fixture input path for independent resource-source verification.
     * Each invocation returns a fresh list; paths are classloader asset paths and contain no game state.
     */
    fun resourcePaths(): List<String> =
        FontCase.entries.map { "assets/strata_font_test/font/${it.id.path}.json" } +
            listOf(
                "assets/strata_font_test/textures/font/colored.png",
                "assets/strata_font_test/font/shapes.zip",
                "assets/strata_font_test/font/strata-test.ttf",
            )

    private fun clear(
        width: Int,
        height: Int,
    ): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height))

    /**
     * Immutable row definition shared by the independently implemented native screen and portable Text scene.
     */
    data class Row(
        val font: ResourceId,
        val text: String,
        val style: TextStyle,
        val top: Int,
    ) {
        /**
         * Exact public TextStyle foreground sent independently to the native draw call.
         */
        val color: Int
            get() =
                when (style) {
                    TextStyle.Normal -> -1
                    TextStyle.Inactive -> 0xFFA0A0A0.toInt()
                    TextStyle.ContainerLabel -> 0xFF404040.toInt()
                    TextStyle.TextField -> error("TextField palette is not part of this static Text scene.")
                }

        /**
         * Native shadow flag corresponding to the selected public TextStyle.
         */
        val shadow: Boolean get() = style != TextStyle.ContainerLabel
    }

    /**
     * Supported provider fixtures; the reference case is verified by whole-string native rendering.
     */
    enum class FontCase(
        path: String,
        val providerIndex: Int? = 0,
        val codePoints: List<Int> = listOf(0x41, 0x65E5, 0xD55C, 0x1F642),
    ) {
        /**
         * Original colored and translucent bitmap cells at integer logical size.
         */
        Bitmap("bitmap"),

        /**
         * The same resource class with fractional source-to-logical scaling.
         */
        FractionalBitmap("bitmap_fractional"),

        /**
         * Original monochrome Unihex rows, including the version-dependent odd-width advance case.
         */
        Unihex("unihex", codePoints = listOf(0x41, 0x65E5, 0xD55C, 0x1F642, 0x45)),

        /**
         * Original TrueType outlines and a non-rasterized space at the standard test size.
         */
        TrueType("ttf", codePoints = listOf(0x20, 0x41, 0x65E5, 0xD55C, 0x1F642)),

        /**
         * Original TrueType outlines with fractional sizing and shifts.
         */
        FractionalTrueType("ttf_fractional", codePoints = listOf(0x20, 0x41, 0x65E5, 0xD55C, 0x1F642)),

        /**
         * Native reference expansion and fallback share the original provider definitions.
         */
        Reference("reference", providerIndex = null),
        ;

        /**
         * Resource identifier resolved by both engines.
         */
        val id: ResourceId = ResourceId("strata_font_test", path)
    }

    /**
     * Raster and shadow-order capabilities for the representative native processes; fixtures use no overlays or filters.
     */
    enum class Target(
        val version: String,
        val compatibility: MinecraftFontCompatibility,
    ) {
        /**
         * The supported release floor uses STB and separate whole-run shadow rendering.
         */
        LegacyStb("1.20", MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.Stb, 0, providerFilters = false, packOverlays = false, minorPackFormats = false, interleavedShadows = false)),

        /**
         * The first supported FreeType generation retains separate whole-run shadows.
         */
        LegacyFreeType("1.20.5", MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 0, minorPackFormats = false, interleavedShadows = false)),

        /**
         * The current native oracle uses interleaved shadows and fractional Unihex advances.
         */
        CurrentFreeType("26.2", MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 0, fractionalUnihexAdvance = true, rejectMalformedOverlayMetadata = true, bakedGlyphMetrics = true, saturatingCeil = true, preparedTextBounds = true)),
        ;

        /**
         * Decodes the native runner's explicit target without inferring release behavior.
         */
        companion object {
            /**
             * Rejects unconfigured or unsupported native runner versions at the external boundary.
             */
            fun parse(version: String?): Target = checkNotNull(entries.singleOrNull { it.version == version }) { "Unsupported font parity target: $version" }
        }
    }
}
