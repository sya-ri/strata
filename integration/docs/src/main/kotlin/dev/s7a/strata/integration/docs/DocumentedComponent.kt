package dev.s7a.strata.integration.docs

/**
 * Typed Minecraft component identities discovered at the runtime API boundary and used by the showcase catalog.
 */
internal enum class DocumentedComponent(
    internal val apiMethodName: String,
    internal val slug: String,
) {
    /**
     * The text component identity.
     */
    Text("Text", "text"),

    /**
     * The editable single-line field component identity.
     */
    TextField("TextField", "text-field"),

    /**
     * The pointer-button component identity.
     */
    Button("Button", "button"),

    /**
     * The menu-list scroll viewport component identity.
     */
    Scroll("Scroll", "scroll"),

    /**
     * The container slot component identity.
     */
    Slot("Slot", "slot"),
    ;

    /**
     * Decoding operations for external API method names.
     */
    companion object {
        /**
         * Decodes an external API method name at the inventory boundary.
         *
         * @param name raw JVM method name.
         * @return the typed identity or null when the name is not a documented component.
         */
        internal fun fromApiMethodName(name: String): DocumentedComponent? = entries.firstOrNull { component -> component.apiMethodName == name }
    }
}
