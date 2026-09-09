package dev.s7a.strata.modifier

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.state.StateSource

/**
 * Runs one action for a primary pointer press or a focused Enter or Space press and consumes that event.
 *
 * This modifier makes its logical component focusable through its keyboard handler.
 * Keyboard activation is delivered after any inner focused handler and includes repeated press deliveries; it does not synthesize a pointer event.
 * Compose this instead of a separate simple [onPress] handler when pointer and keyboard input represent the same action, so one nearest consuming handler owns that shared action consistently.
 *
 * @param action synchronous action invoked on the owning tree thread.
 * @return this chain with one primary-press handler followed by one focused key-press handler.
 * @throws Throwable when [action] fails during input dispatch; the owning tree preserves the exact failure as primary while poisoning and cleaning retained ownership.
 */
public fun Modifier.onActivate(action: () -> Unit): Modifier =
    onPress(action).onKeyPress { event ->
        if (event.key == KeyCode.Enter || event.key == KeyCode.Space) {
            action()
            InputResult.Consumed
        } else {
            InputResult.Ignored
        }
    }

/**
 * Conditionally installs the shared pointer and keyboard activation behavior from [onActivate].
 *
 * A false [enabled] value returns this exact modifier unchanged, retains no [action], and adds no pointer or focus target node.
 * Components do not expose their enabled state to modifiers, so callers pass the same state explicitly when disabled appearance and activation must agree.
 *
 * @param enabled whether this chain accepts activation input.
 * @param action synchronous action invoked for enabled activation.
 * @return [onActivate] behavior when enabled, otherwise this exact modifier.
 * @throws Throwable when enabled [action] fails during input dispatch.
 */
public fun Modifier.onActivate(
    enabled: Boolean,
    action: () -> Unit,
): Modifier = if (enabled) onActivate(action) else this

/**
 * Keeps activation attached while its enabled value follows a frame-committed source.
 * Pass the same source to the component's enabled argument when appearance and input must agree.
 * Unlike the Boolean overload, a false source retains the action so it can become enabled without rebuilding its parent.
 * Notifications never dispatch actions; pointer and keyboard input use the last committed value on the owner thread.
 * The caller owns the source. Replacement, disposal, failure, and close release the runtime observation and action.
 *
 * @param enabled caller-owned source controlling this modifier's focus eligibility and activation.
 * @param action synchronous owner-thread action with the ordinary input failure contract.
 * @return this chain with one retained source-backed activation node.
 */
public fun Modifier.onActivate(
    enabled: StateSource<Boolean>,
    action: () -> Unit,
): Modifier = then(ObservedActivationModifier(enabled, action))
