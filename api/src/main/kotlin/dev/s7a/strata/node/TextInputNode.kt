package dev.s7a.strata.node

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent

/**
 * Retained capability that receives committed text and preedit events while its logical component owns focus.
 *
 * Calls are synchronous on the owning tree thread and must not re-enter tree operations.
 *
 * @see dev.s7a.strata.modifier.onTextInput
 */
public interface TextInputNode {
    /**
     * Handles one focused text-input event.
     *
     * @param event immutable committed-character or preedit event.
     * @return consumed to stop delivery through earlier modifier nodes, otherwise ignored.
     * @throws Throwable when component behavior fails; the retained runtime preserves the exact failure as primary.
     */
    public fun onTextInput(event: TextInputEvent): InputResult
}
