package dev.s7a.strata.component

import dev.s7a.strata.internal.platform.currentOwner
import dev.s7a.strata.internal.platform.scalarAt
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.mutableStateOf

/**
 * Owner-confined mutable value for one single-line text field.
 *
 * The state accepts well-formed Unicode text excluding C0 controls, DEL, NEL (U+0085), line and paragraph separators (U+2028/U+2029), and the section-sign formatting marker.
 * It owns its value and permits at most one live retained observer.
 * Reads, writes, observation, and subscription release are confined to the execution owner that constructs the state.
 *
 * @param initialValue initial well-formed single-line text.
 * @property maxLength positive maximum UTF-16 length accepted by [TextFieldState.value].
 * @throws IllegalArgumentException when [TextFieldState.maxLength] is not positive or `initialValue` is unsupported or too long.
 */
public class TextFieldState(
    initialValue: String = "",
    public val maxLength: Int = 32,
) {
    private val owner = currentOwner()
    private var observer: ((String) -> Unit)? = null
    private val currentValue: MutableState<String>

    init {
        require(0 < maxLength) { "TextField maximum length must be positive." }
        currentValue = mutableStateOf(validate(initialValue))
    }

    /**
     * Current well-formed single-line Unicode value.
     *
     * A distinct successful write synchronously notifies the attached retained observer.
     *
     * @throws IllegalArgumentException when a value contains unsupported text or exceeds [TextFieldState.maxLength].
     * @throws IllegalStateException when accessed from another execution owner.
     */
    public var value: String
        get() {
            checkOwner()
            return currentValue.value
        }
        set(value) {
            checkOwner()
            val validated = validate(value)
            if (currentValue.update(validated).not()) return
            observer?.invoke(validated)
        }

    /**
     * Installs the sole retained observer used by a runtime text-field node.
     *
     * This privileged bridge is not an application event API.
     * The returned release operation is idempotent and confined to the execution owner.
     *
     * @param callback callback invoked synchronously after each distinct successful write.
     * @return an idempotent subscription release operation.
     * @throws IllegalStateException when called from another execution owner or while another observer is live.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (String) -> Unit): AutoCloseable {
        checkOwner()
        check(observer == null) { "TextField state already has a live observer." }
        observer = callback
        var released = false
        return AutoCloseable {
            checkOwner()
            if (released.not()) {
                released = true
                if (observer === callback) {
                    observer = null
                }
            }
        }
    }

    private fun validate(value: String): String {
        require(value.length <= maxLength) { "TextField value exceeds its maximum length." }
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.scalarAt(offset)
            require((codePoint in 0xD800..0xDFFF).not()) { "TextField value contains an isolated surrogate." }
            require(isAcceptedCodePoint(codePoint)) {
                "TextField value contains a control character, line separator, or formatting marker."
            }
            offset += (if (codePoint < 0x10000) 1 else 2)
        }
        return value
    }

    private fun isAcceptedCodePoint(codePoint: Int): Boolean =
        when (codePoint) {
            0x7F, 0x85, 0xA7, 0x2028, 0x2029 -> false
            else -> 0x20 <= codePoint
        }

    private fun checkOwner() {
        check(currentOwner() == owner) { "TextField state requires its execution owner." }
    }
}
