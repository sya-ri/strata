package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Supplies immutable text and logical positions to independent native and portable readability renderers.
 * The scene uses the active default font with opaque container-label text, so no shadow or alpha-blend tolerance is needed.
 * No measured native glyph or screenshot is used to construct the portable scene.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftTextReadabilityScene {
    const val BACKGROUND: Int = -1
    const val FOREGROUND: Int = -12_566_464
    const val LEFT: Int = 16
    val viewport: IntSize = IntSize(320, 240)
    val rows: List<Row> = listOf(Row("日本語の表示を確認します。", 16), Row("한글 / 🙂", 34))

    /**
     * Creates a one-shot public Text scene using the profile's default resource font and unshadowed container-label style.
     */
    fun definition(): ScreenDefinition =
        ScreenDefinition("Default font readability") {
            Stack(modifier = Modifier.Empty.fillMaxSize().background(ArgbColor(BACKGROUND))) {
                rows.forEach { row ->
                    Text(row.text, style = TextStyle.ContainerLabel, modifier = Modifier.Empty.padding(left = LEFT, top = row.top))
                }
            }
        }

    /**
     * Renders a caller-supplied component definition at its final physical density using an independently owned portable host.
     * Host and font-backend resources close before returning detached pixels, including on failure.
     *
     * @param profile immutable active resource snapshot, borrowed without modification.
     * @param scale positive physical pixels per logical GUI pixel.
     * @param definition one-shot component scene consumed on the client thread.
     * @param viewport finite logical extent shared with the corresponding native scene or labelled preview.
     * @return newly rasterized pixels; this never rescales a previously rendered image.
     */
    fun render(
        profile: MinecraftUiProfile,
        scale: Int,
        definition: ScreenDefinition = definition(),
        viewport: IntSize = this.viewport,
    ): HeadlessImage =
        createMinecraftUiHost(definition, profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val frame = host.frame(viewport)
            rasterizeHeadless(frame.drawCommands, frame.size, scale)
        }

    /**
     * One immutable literal and logical top position; neither field is derived from a renderer.
     */
    data class Row(
        val text: String,
        val top: Int,
    )
}
