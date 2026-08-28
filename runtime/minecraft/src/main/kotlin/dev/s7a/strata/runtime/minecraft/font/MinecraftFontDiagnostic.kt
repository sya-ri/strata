package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId

/**
 * Immutable diagnostic for a source, metadata section, font document, or provider excluded from a snapshot.
 * Diagnostic strings describe external provenance and never select runtime behavior.
 *
 * @property kind typed failure category.
 * @property font affected font family, or null for pack-wide metadata failures and unsupported document identifiers.
 * @property source originating pack label.
 * @property message description of the invalid resource.
 */
public data class MinecraftFontDiagnostic(
    public val kind: Kind,
    public val font: ResourceId?,
    public val source: String,
    public val message: String,
) {
    /**
     * Distinguishes invalid pack metadata, skipped documents, and unusable provider graphs.
     */
    public enum class Kind {
        /**
         * A selected source or metadata section was excluded because its metadata could not be read or parsed.
         */
        PackMetadataFailure,

        /**
         * An unreadable or malformed document was skipped before provider accumulation.
         */
        MalformedDocument,

        /**
         * A provider's asset could not be loaded.
         */
        ProviderLoadFailure,

        /**
         * A referenced font family does not exist.
         */
        MissingReference,

        /**
         * A reference cycle prevents the complete font bundle from resolving.
         */
        CyclicReference,
    }
}
