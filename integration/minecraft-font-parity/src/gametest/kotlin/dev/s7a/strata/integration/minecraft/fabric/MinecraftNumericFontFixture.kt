package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Separate numeric provider inputs using the original tiny, trusted TrueType fixture.
 * The generated JSON is never installed in Minecraft's global resource pack or startup reload.
 * Native tests load the same bytes into owned FontSets only after their raster safety checks.
 * Portable callers own each fresh host; every returned command and pixel remains detached after close.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftNumericFontFixture {
    /**
     * A central origin keeps finite negative advances inside the unchanged 320 by 240 proof viewport.
     */
    const val LEFT: Int = 160

    /**
     * The independently loaded ordinary font supplies visible text before and after a non-finite cursor.
     */
    val regularFont: ResourceId = ResourceId("strata_font_test", "ttf")

    /**
     * Raw probes include an empty glyph, four real outlines, and an absent character.
     */
    val codePoints: List<Int> = listOf(0x20, 0x41, 0x65E5, 0xD55C, 0x1F642, 0x3A9)

    /**
     * Two rows per case separate an unbroken numeric run from a finite prefix followed by a possibly poisoned cursor.
     */
    val rows: List<Row> =
        Case.entries.flatMapIndexed { index, case ->
            listOf(
                Row(listOf(Segment(case.id, "A日한🙂")), TextStyle.Normal, 8 + index * 30, if (case == Case.PositiveZero) 0 else LEFT),
                Row(listOf(Segment(regularFont, "A"), Segment(case.id, " A"), Segment(regularFont, "AΩ")), TextStyle.ContainerLabel, 20 + index * 30),
            )
        }

    /**
     * Returns the actual TrueType provider object encoded for native codecs and the independent pack parser.
     * Float conversion retains a negative zero spelling; no non-finite JSON number is introduced.
     */
    fun providerJson(case: Case): String = """{"type":"ttf","file":"strata_font_test:strata-test.ttf","size":${case.size},"oversample":${case.oversample},"shift":[0.0,0.0]}"""

    /**
     * Copies all unchanged original assets and adds only the isolated numeric font documents.
     */
    fun resources(): Map<String, ByteArray> =
        MinecraftFontParityFixture.resourcePaths().associateWith(MinecraftFontParityFixture::bytes).toMutableMap().apply {
            Case.entries.forEach { case -> put(case.path, "{\"providers\":[${providerJson(case)}]}\n".toByteArray()) }
        }

    /**
     * Loads the isolated documents without a game class or graphics context and rejects unexpected parser diagnostics.
     */
    fun snapshot(compatibility: MinecraftFontCompatibility): MinecraftFontSnapshot =
        MinecraftFontSnapshot.load(listOf(MinecraftMemoryFontAssetSource("Isolated numeric font fixtures", resources())), compatibility).also {
            check(it.diagnostics.isEmpty()) { "Numeric fixture parsing failed: ${it.diagnostics}" }
        }

    /**
     * Records unchanged original input hashes plus every numeric document and row, without claiming native parity.
     */
    fun metadata(compatibility: MinecraftFontCompatibility): Map<String, String> =
        MinecraftFontParityFixture.evidenceMetadata(compatibility).toMutableMap().apply {
            resources().forEach { (path, bytes) -> put("input.$path.sha256", MinecraftFontParityFixture.sha256(bytes)) }
            put("numeric.scene.sha256", MinecraftFontParityFixture.sha256(rows.joinToString("\n", prefix = "$LEFT\n", postfix = "\n").toByteArray()))
        }

    /**
     * Builds the public Text scene without copying native layout or native pixels.
     */
    fun definition(): ScreenDefinition =
        ScreenDefinition("Independent numeric font parity") {
            Stack(modifier = Modifier.Empty.fillMaxSize().background(ArgbColor(MinecraftFontParityFixture.background))) {
                rows.forEach { row -> Text(row.text(), row.style, modifier = MinecraftNumericFontAperture.modifier(row)) }
            }
        }

    /**
     * Returns actual portable commands from a closed, independently created host.
     */
    fun commands(profile: MinecraftUiProfile): List<DrawCommand> =
        createMinecraftUiHost(definition(), profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(MinecraftFontParityFixture.viewport).drawCommands.toList()
        }

    /**
     * Rasterizes original-resource commands only at the final requested physical scale.
     */
    fun render(
        profile: MinecraftUiProfile,
        scale: Int,
    ): HeadlessImage = rasterizeHeadless(commands(profile), MinecraftFontParityFixture.viewport, scale)

    /**
     * One independently styled text span; the native component and public UiText consume these same literal inputs.
     */
    data class Segment(
        val font: ResourceId,
        val text: String,
    )

    /**
     * A native logical row with no assumed non-negative measured width.
     */
    data class Row(
        val segments: List<Segment>,
        val style: TextStyle,
        val top: Int,
        val left: Int = LEFT,
    ) {
        /**
         * Preserves every font boundary and literal for public Text rendering.
         */
        fun text(): UiText = UiText.Concatenated(segments.map { UiText.WithFont(UiText.Literal(it.text), it.font) })

        /**
         * Exact public style color supplied to the native draw path.
         */
        val color: Int get() = if (style == TextStyle.Normal) -1 else 0xFF404040.toInt()

        /**
         * The numeric-only row uses native shadows; the mixed row isolates foreground cursor behavior.
         */
        val shadow: Boolean get() = style == TextStyle.Normal
    }

    /**
     * Bounded numeric definitions selected before any native execution.
     * The negative-product FreeType cases permit metrics-only work, with atlas rejection before raster allocation.
     */
    enum class Case(
        val size: Float,
        val oversample: Float,
    ) {
        /**
         * Small negative size rounds to the minimum FreeType pixel size.
         */
        SmallNegative(-0.25f, 1f),

        /**
         * Positive zero oversampling exposes native NaN and positive-infinite metrics.
         */
        PositiveZero(11f, 0f),

        /**
         * Negative zero retains its distinct IEEE divisor bits.
         */
        NegativeZero(11f, -0.0f),

        /**
         * Two negative inputs produce a bounded positive raster and reversed logical ink.
         */
        Reversed(-11f, -2f),

        /**
         * Negative pixel size reaches native unsigned sizing; oversized raster upload is forbidden by the atlas.
         */
        NegativeSize(-11f, 1f),

        /**
         * Negative oversampling produces the same native oversized sizing with negative logical metrics.
         */
        NegativeOversample(11f, -2f),

        /**
         * A subpixel negative product rounds to zero while retaining reversed logical ink.
         */
        SmallNegativeOversample(0.25f, -1f),
        ;

        /**
         * Structural identifier unique to this isolated document, decoded without game-version string dispatch.
         */
        val id: ResourceId = ResourceId("strata_numeric_font_test", name.lowercase())

        /**
         * Detached resource-stack path; this file is deliberately absent from the native startup pack.
         */
        val path: String = "assets/${id.namespace}/font/${id.path}.json"

        /**
         * Exact finite provider settings supplied independently to the CPU backend raw-glyph probe.
         */
        fun settings(): MinecraftTrueTypeSettings = MinecraftTrueTypeSettings(size, oversample)
    }
}
