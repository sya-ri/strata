package dev.s7a.strata.integration.docs

/**
 * Typed component identities discovered at the API boundary and used by the showcase catalog.
 */
internal enum class DocumentedComponent(
    internal val apiMethodName: String,
    internal val slug: String,
) {
    /**
     * The row component identity.
     */
    Row("Row", "row"),

    /**
     * The column component identity.
     */
    Column("Column", "column"),

    /**
     * The box component identity.
     */
    Box("Box", "box"),

    /**
     * The spacer component identity.
     */
    Spacer("Spacer", "spacer"),
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
