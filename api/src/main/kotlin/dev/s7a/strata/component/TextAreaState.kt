package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread mutable text and vertical scroll position for one multiline editor.
 *
 * Accepted input is well-formed Unicode with LF line breaks.
 * CRLF, CR, VT, FF, NEL, line separator, and paragraph separator normalize to LF before the UTF-16 length limit is applied.
 * Other C0 controls, DEL, isolated surrogates, and the section-sign formatting marker are rejected without changing state.
 * Values own no retained nodes; one live text observer prevents simultaneous editors from sharing this state.
 * Reads, writes, observation, and subscription release are confined to the constructing thread.
 *
 * @param initialValue initial text, normalized before storage.
 * @property maxLength positive maximum UTF-16 length of the normalized [value].
 * @throws IllegalArgumentException when [maxLength] is not positive or [initialValue] is unsupported or too long after normalization.
 */
public class TextAreaState(
    initialValue: String = "",
    public val maxLength: Int = 32767,
) {
    private val ownerThread: Thread = Thread.currentThread()
    private val ownedScrollState: ScrollState = ScrollState()
    private var observer: ((String) -> Unit)? = null
    private var currentValue: String

    init {
        require(0 < maxLength) { "Text area maximum length must be positive." }
        currentValue = normalize(initialValue)
    }

    /**
     * Current well-formed Unicode text with canonical LF line breaks.
     *
     * A distinct normalized write synchronously notifies the attached retained observer.
     * Equivalent newline spellings do not notify again or replace the owned scroll state.
     *
     * @throws IllegalArgumentException when text is unsupported or its normalized UTF-16 length exceeds [maxLength].
     * @throws IllegalStateException when accessed from another thread.
     */
    public var value: String
        get() {
            checkThread()
            return currentValue
        }
        set(value) {
            checkThread()
            val normalized = normalize(value)
            if (currentValue == normalized) return
            currentValue = normalized
            observer?.invoke(normalized)
        }

    /**
     * Stable owned vertical position shared by the editor and optional external scrollbars.
     *
     * Its lifetime matches this state and it is not replaced by value writes or observer release.
     * Runtime geometry clamps the position after layout; assigning [value] alone does not reset it.
     *
     * @throws IllegalStateException when accessed from another thread.
     */
    public val scrollState: ScrollState
        get() {
            checkThread()
            return ownedScrollState
        }

    /**
     * Installs the sole retained text observer used by a runtime text-area node.
     *
     * This privileged bridge is not an application event API.
     * The returned release operation is idempotent and owner-thread confined.
     * Releasing it does not release the caller-owned text or scroll state.
     *
     * @param callback callback invoked synchronously after each distinct normalized write.
     * @return an idempotent subscription release operation.
     * @throws IllegalStateException when called from another thread or while another text observer is live.
     */
    @InternalStrataRuntimeApi
    public fun observe(callback: (String) -> Unit): AutoCloseable {
        checkThread()
        check(observer == null) { "Text area state already has a live observer." }
        observer = callback
        var released = false
        return AutoCloseable {
            checkThread()
            if (released.not()) {
                released = true
                if (observer === callback) observer = null
            }
        }
    }

    private fun normalize(value: String): String {
        val result = StringBuilder(minOf(value.length, maxLength))
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            require((codePoint in 0xD800..0xDFFF).not()) { "Text area value contains an isolated surrogate." }
            when (codePoint) {
                0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029 -> {
                    result.append('\n')
                    if (codePoint == 0x0D && offset + 1 < value.length && value[offset + 1] == '\n') offset += 1
                }

                else -> {
                    require(0x20 <= codePoint && codePoint != 0x7F && codePoint != 0xA7) {
                        "Text area value contains a control character or formatting marker."
                    }
                    result.appendCodePoint(codePoint)
                }
            }
            require(result.length <= maxLength) { "Text area value exceeds its maximum length after newline normalization." }
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "Text area state requires its creator thread." }
    }
}
