package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap

/**
 * Immutable assets, caller state, and geometry for one text-area description.
 *
 * The renderer is borrowed from the owner-thread host and never closed by an editor.
 * The description owns neither its state nor native font handles; retained nodes clear their configuration on disposal.
 *
 * @property normalSprite detached unfocused frame pixels.
 * @property highlightedSprite detached focused frame pixels.
 * @property renderer borrowed owner-thread text service.
 * @property font resource identifier used for every editor scalar.
 * @property state caller-owned canonical multiline value and vertical position.
 * @param viewport fixed outer size or requested visible line count.
 * @property enabled whether focus and editing are accepted.
 * @property style profile-backed text colors and shadows.
 * @param wrap presentation-only wrapping policy.
 * @param lineSpacing non-negative extra logical pixels between lines.
 * @throws IllegalArgumentException when the requested extent cannot contain the frame and a positive inner viewport.
 * @throws ArithmeticException when line-count sizing exceeds portable integer geometry.
 */
internal class MinecraftTextAreaConfiguration(
    @get:JvmSynthetic internal val normalSprite: DrawImage,
    @get:JvmSynthetic internal val highlightedSprite: DrawImage,
    @get:JvmSynthetic internal val renderer: MinecraftTextRenderer,
    @get:JvmSynthetic internal val font: ResourceId,
    @get:JvmSynthetic internal val state: TextAreaState,
    viewport: TextAreaViewport,
    @get:JvmSynthetic internal val enabled: Boolean,
    @get:JvmSynthetic internal val style: TextStyle,
    wrap: TextWrap,
    lineSpacing: Int,
) {
    /**
     * Complete editable layout policy; content is never truncated to the viewport's height.
     */
    @get:JvmSynthetic
    internal val policy: TextLayout.Multiline = TextLayout.Multiline(wrap = wrap, lineSpacing = lineSpacing)

    /**
     * Exact outer frame size including four logical pixels of padding on each side.
     */
    @get:JvmSynthetic
    internal val size: IntSize =
        when (viewport) {
            is TextAreaViewport.Size -> {
                viewport.size
            }

            is TextAreaViewport.Lines -> {
                IntSize(viewport.width, Math.addExact(Math.addExact(Math.multiplyExact(viewport.lines, 9), Math.multiplyExact(viewport.lines - 1, lineSpacing)), 8))
            }
        }

    /**
     * Positive clipped text viewport excluding the frame padding.
     */
    @get:JvmSynthetic
    internal val innerSize: IntSize

    init {
        require(9 <= size.width && 9 <= size.height) { "Minecraft TextArea size must be at least 9 by 9." }
        innerSize = IntSize(size.width - 8, size.height - 8)
        require(state.value.length <= state.maxLength) { "Minecraft TextArea state exceeds its maximum length." }
    }

    /**
     * Tests whether a retained update leaves the current text layout and state attachment unchanged.
     *
     * @param other next immutable description's inputs.
     * @return true when the detached current layout can be retained; frame sprites are compared separately by the editor.
     */
    @JvmSynthetic
    internal fun hasSameLayout(other: MinecraftTextAreaConfiguration): Boolean = renderer === other.renderer && font == other.font && state === other.state && size == other.size && enabled == other.enabled && style == other.style && policy == other.policy
}
