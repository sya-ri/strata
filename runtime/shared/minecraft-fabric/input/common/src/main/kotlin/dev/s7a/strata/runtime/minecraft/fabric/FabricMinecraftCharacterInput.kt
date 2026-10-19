package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.TextInputEvent

/**
 * Reassembles adjacent native UTF-16 callbacks into detached Unicode scalars on one screen's client thread.
 * Retains at most one high surrogate; the owner resets it at input-kind, focus, frame and lifecycle boundaries.
 */
internal class FabricMinecraftCharacterInput {
    private var pendingHighSurrogate: Char? = null

    /**
     * Consumes one code unit, returning null for a pending high surrogate or an orphan low surrogate.
     */
    fun accept(character: Char): TextInputEvent.Character? {
        val previous = pendingHighSurrogate
        pendingHighSurrogate = null
        if (Character.isHighSurrogate(character)) {
            pendingHighSurrogate = character
            return null
        }
        if (Character.isLowSurrogate(character)) {
            return if (previous == null) null else TextInputEvent.Character(Character.toCodePoint(previous, character))
        }
        return TextInputEvent.Character(character.code)
    }

    /**
     * Idempotently discards an incomplete pair when the owning input interval ends.
     */
    fun reset() {
        pendingHighSurrogate = null
    }
}
