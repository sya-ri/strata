package dev.s7a.strata.text

/**
 * Immutable wrapping policy shared by displayed multiline text and editable text areas.
 *
 * Wrapping changes presentation only and never inserts characters into caller-owned text.
 * Breaks never split a Unicode scalar; grapheme-cluster navigation and layout are not implied.
 * Values own no runtime resources and may be shared across threads.
 */
public enum class TextWrap {
    /**
     * Uses only explicit line separators and leaves horizontal overflow to the enclosing layout policy.
     */
    None,

    /**
     * Prefers breakable whitespace boundaries and splits an overlong segment at Unicode scalar boundaries.
     * NBSP, figure space, and narrow NBSP are not preferred boundaries; an overlong unbroken segment still uses scalar fallback.
     * This intentionally limited policy does not implement a language-specific line-breaking engine.
     */
    Word,

    /**
     * Wraps at Unicode scalar boundaries without searching for whitespace.
     */
    Character,
}
