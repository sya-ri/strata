package dev.s7a.strata.integration.docs

/**
 * Typed identities for every repository document synchronized by the Strata skill generator.
 *
 * @property relativePath canonical repository-relative destination.
 */
internal enum class StrataGeneratedDocument(
    internal val relativePath: String,
) {
    /**
     * Root reader entrypoint with generated installation and API-only example regions.
     */
    Readme("README.md"),

    /**
     * Canonical long-form body mirrored to the Modrinth project.
     */
    ModrinthProject("docs/modrinth-project.md"),

    /**
     * Skill setup and version-selection reference.
     */
    Setup("skills/strata/references/setup.md"),

    /**
     * Skill standard-component signature catalog.
     */
    Components("skills/strata/references/components.md"),

    /**
     * Skill modifier, parent-scope, state, and binding catalog.
     */
    ModifiersAndLayout("skills/strata/references/modifiers-and-layout.md"),

    /**
     * Skill structural-authoring patterns.
     */
    Patterns("skills/strata/references/patterns.md"),

    /**
     * Skill downstream-component guidance and example.
     */
    CustomComponents("skills/strata/references/custom-components.md"),
    ;

    /**
     * Decodes an external generated path before it participates in destination policy.
     */
    internal companion object {
        private val byRelativePath: Map<String, StrataGeneratedDocument> = entries.associateBy(StrataGeneratedDocument::relativePath)

        /**
         * Decodes one repository-relative generated path.
         *
         * @param relativePath external generated path.
         * @return typed document identity or null when the path is not registered.
         */
        internal fun fromRelativePath(relativePath: String): StrataGeneratedDocument? = byRelativePath[relativePath]
    }
}
