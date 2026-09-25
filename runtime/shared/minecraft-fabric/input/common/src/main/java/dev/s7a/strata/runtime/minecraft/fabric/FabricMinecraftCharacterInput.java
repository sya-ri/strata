package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.input.TextInputEvent;

/**
 * Reassembles the adjacent UTF-16 callbacks emitted for one legacy Minecraft character event.
 *
 * <p>One screen owns this helper on its client thread. It retains at most one high surrogate and emits only complete Unicode scalars. The owner clears pending input before another input kind, a frame, a focus-changing operation, or any lifecycle transition.</p>
 */
final class FabricMinecraftCharacterInput {
    private Character pendingHighSurrogate;

    /**
     * Consumes one UTF-16 unit without retaining any native event or target node.
     *
     * @param character current native UTF-16 unit.
     * @return detached scalar input, or null while awaiting a low surrogate or discarding an orphan low surrogate.
     */
    TextInputEvent.Character accept(char character) {
        Character previous = pendingHighSurrogate;
        pendingHighSurrogate = null;
        if (Character.isHighSurrogate(character)) {
            pendingHighSurrogate = character;
            return null;
        }
        if (Character.isLowSurrogate(character)) {
            if (previous == null) {
                return null;
            }
            return new TextInputEvent.Character(Character.toCodePoint(previous, character));
        }
        return new TextInputEvent.Character(character);
    }

    /**
     * Discards any incomplete pair when its screen or focused-input interval ends.
     *
     * <p>The operation is idempotent and must run on the owning client thread.</p>
     */
    void reset() {
        pendingHighSurrogate = null;
    }
}
