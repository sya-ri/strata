package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Shares the immutable ASCII profile fixture and host construction used by field behavior tests.
 *
 * Every host call creates independent retained ownership on the calling thread and borrows the supplied field state.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftTextFieldFixture {
    /**
     * Immutable native default field extent shared by rendering and input tests.
     */
    val fieldSize = IntSize(200, 20)

    /**
     * Creates an unevaluated field host backed by the existing ASCII profile fixture.
     *
     * @param state caller-owned state created on the current thread.
     * @param modifier retained input, focus, or layout behavior for this field.
     * @param size requested field extent, validated when the host attaches.
     * @param textStyle profile text color and shadow treatment.
     * @return independent owner-thread host that the caller attaches, drives, and closes.
     */
    fun host(
        state: TextFieldState,
        modifier: Modifier = Modifier.Empty,
        size: IntSize = fieldSize,
        textStyle: TextStyle = TextStyle.TextField,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition(UiText.Literal("TextField")) {
                TextField(state, size = size, textStyle = textStyle, modifier = modifier)
            },
            MinecraftProfileFixture.create(),
        )
}
