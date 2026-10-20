package dev.s7a.strata.runtime.web

import dev.s7a.strata.input.TextInputEvent

/**
 * Produces committed scalar notifications for one native replacement, keeping surrogate pairs intact.
 * The native editor already owns replacement and selection; these events notify application modifiers only.
 */
internal fun committedWebText(
    before: String,
    after: String,
): List<TextInputEvent.Character> {
    var prefix = before.commonPrefixWith(after).length
    if (0 < prefix && prefix < after.length && after[prefix].isLowSurrogate()) prefix -= 1
    val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
    val end = after.length - suffix
    val result = ArrayList<TextInputEvent.Character>()
    var index = prefix
    while (index < end) {
        val first = after[index]
        val codePoint =
            if (first.isHighSurrogate() && index + 1 < after.length) {
                val second = after[index + 1]
                index += 1
                0x10000 + ((first.code - 0xD800) shl 10) + second.code - 0xDC00
            } else {
                first.code
            }
        result.add(TextInputEvent.Character(codePoint))
        index += 1
    }
    return result
}
