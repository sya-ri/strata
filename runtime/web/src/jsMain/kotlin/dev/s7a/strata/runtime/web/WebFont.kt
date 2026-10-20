package dev.s7a.strata.runtime.web

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * One explicitly loaded browser font and its logical size.
 * The caller closes it after borrowing hosts; close removes its FontFace from this document.
 * This does not resolve a Minecraft font manifest or silently substitute a system font.
 */
public class WebFont private constructor(
    private var face: dynamic,
    private val fontCss: String,
) : AutoCloseable {
    private var closed = false
    internal val css: String get() {
        check(closed.not()) { "The prepared Web font is closed." }
        return fontCss
    }

    override fun close() {
        if (closed) return
        closed = true
        document.asDynamic().fonts.delete(face)
        face = null
    }

    /**
     * Owns preparation before a font can enter a resource snapshot.
     */
    public companion object {
        /**
         * Loads a licensed TTF/OTF/WOFF resource under an application-chosen unique family.
         * The URL follows browser CSP/CORS rules. Failed or cancelled loading installs no font.
         * Every required glyph must exist in the supplied face; applications own font coverage and resource-pack conversion.
         */
        public suspend fun load(
            family: String,
            url: String,
            size: Int = 9,
        ): WebFont {
            require(family.matches(Regex("[A-Za-z][A-Za-z0-9_-]*"))) { "Web font family must be a safe unique identifier." }
            require(url.isNotBlank() && 0 < size) { "Web font URL and size must be valid." }
            val source = "url(${JSON.stringify(url)})"
            val face = js("new FontFace(family, source)")
            (face.load() as Promise<dynamic>).await()
            document.asDynamic().fonts.add(face)
            return WebFont(face, "${size}px '$family'")
        }
    }
}
