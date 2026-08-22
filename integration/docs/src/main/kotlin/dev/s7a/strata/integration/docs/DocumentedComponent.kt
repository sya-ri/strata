package dev.s7a.strata.integration.docs

/**
 * Typed standard component identities discovered from the public API artifact and used by the showcase catalog.
 */
internal enum class DocumentedComponent(
    internal val apiMethodName: String,
    internal val slug: String,
) {
    /**
     * The horizontal linear-layout component identity.
     */
    Row("Row", "row"),

    /**
     * The vertical linear-layout component identity.
     */
    Column("Column", "column"),

    /**
     * The overlay-layout component identity.
     */
    Stack("Stack", "stack"),

    /**
     * The fixed-column grid-layout component identity.
     */
    Grid("Grid", "grid"),

    /**
     * The empty layout-footprint component identity.
     */
    Spacer("Spacer", "spacer"),

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
     * The externally selected tab component identity.
     */
    Tab("Tab", "tab"),

    /**
     * The menu-list scroll viewport component identity.
     */
    Scroll("Scroll", "scroll"),

    /**
     * The immutable nearest-sampled image component identity.
     */
    Image("Image", "image"),

    /**
     * The container slot component identity.
     */
    Slot("Slot", "slot"),

    /**
     * The reusable layered player-head component identity.
     */
    PlayerHead("PlayerHead", "player-head"),
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
