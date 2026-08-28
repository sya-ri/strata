package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread mutable value for one single-line text field.
 *
 * The state accepts well-formed Unicode text excluding C0 controls, DEL, NEL (U+0085), line and paragraph separators (U+2028/U+2029), and the section-sign formatting marker.
 * It owns its value and permits at most one live retained observer.
 * Reads, writes, observation, and subscription release are confined to the thread that constructs the state.
 *
 * @param initialValue initial well-formed single-line text.
 * @property maxLength positive maximum UTF-16 length accepted by [value].
 * @throws IllegalArgumentException when [maxLength] is not positive or [initialValue] is unsupported or too long.
 */
public class TextFieldState(
    initialValue: String = "",
    public val maxLength: Int = 32,
) {
    private val ownerThread: Thread = Thread.currentThread()
    private var observer: ((String) -> Unit)? = null
    private var currentValue: String

    init {
        require(0 < maxLength) { "TextField maximum length must be positive." }
        currentValue = validate(initialValue)
    }

    /**
     * Current well-formed single-line Unicode value.
     *
     * A distinct successful write synchronously notifies the attached retained observer.
     *
     * @throws IllegalArgumentException when a value contains unsupported text or exceeds [maxLength].
     * @throws IllegalStateException when accessed from another thread.
     */
    public var value: String
        get() {
            checkThread()
            return currentValue
        }
        set(value) {
            checkThread()
            val validated = validate(value)
            if (currentValue == validated) return
            currentValue = validated
            observer?.invoke(validated)
        }

    /**
     * Installs the sole retained observer used by a runtime text-field node.
     *
     * This privileged bridge is not an application event API.
     * The returned release operation is idempotent and owner-thread confined.
     *
     * @param callback callback invoked synchronously after each distinct successful write.
     * @return an idempotent subscription release operation.
     * @throws IllegalStateException when called from another thread or while another observer is live.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (String) -> Unit): AutoCloseable {
        checkThread()
        check(observer == null) { "TextField state already has a live observer." }
        observer = callback
        var released = false
        return AutoCloseable {
            checkThread()
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
            val codePoint = value.codePointAt(offset)
            require((codePoint in 0xD800..0xDFFF).not()) { "TextField value contains an isolated surrogate." }
            require(isAcceptedCodePoint(codePoint)) {
                "TextField value contains a control character, line separator, or formatting marker."
            }
            offset += Character.charCount(codePoint)
        }
        return value
    }

    private fun isAcceptedCodePoint(codePoint: Int): Boolean =
        when (codePoint) {
            0x7F, 0x85, 0xA7, 0x2028, 0x2029 -> false
            else -> 0x20 <= codePoint
        }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "TextField state requires its creator thread." }
    }
}
