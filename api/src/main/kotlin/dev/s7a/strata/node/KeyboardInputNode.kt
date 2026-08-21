package dev.s7a.strata.node

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent

/**
 * Retained capability that receives keyboard events while its logical component owns focus.
 *
 * Calls are synchronous on the owning tree thread and must not re-enter tree operations.
 *
 * @see dev.s7a.strata.modifier.onKeyEvent
 */
public interface KeyboardInputNode {
    /**
     * Handles one focused keyboard event.
     *
     * @param event immutable keyboard event.
     * @return consumed to stop delivery through earlier modifier nodes, otherwise ignored.
     * @throws Throwable when component behavior fails; the retained runtime preserves the exact failure as primary.
     */
    public fun onKeyboardEvent(event: KeyboardEvent): InputResult
}
