package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.input.KeyCode

/**
 * Independent loaded-adapter transitions complementing the complete Minecraft-independent CPU corpus.
 * Expected selection and focus are literal case inputs, never derived from a candidate frame or state result.
 * Enum names identify newly generated screenshots and receipts across all supported adapter families.
 */
internal enum class MinecraftCycleButtonSelectionCase(
    internal val expectedValue: Int,
    internal val key: KeyCode? = null,
    internal val focused: Boolean = false,
) {
    UnchangedHover(1023),
    PointerForwardWrap(0, focused = true),
    ScrollForwardWrap(0),
    ScrollBackward(1022),
    KeyLeft(1022, KeyCode.Left, true),
    KeyRight(0, KeyCode.Right, true),
    KeySpace(0, KeyCode.Space, true),
    KeyEnter(0, KeyCode.Enter, true),
    DisabledInputs(1023),
    ZeroScroll(1023),
    ExternalWrite(0),
    EarlierObserverFailure(0),
    LaterObserverReentry(1, focused = true),
    ActionReentry(1, focused = true),
    StateAndLabelReplacement(1023),
    DetachedMutationReattach(0),
}
