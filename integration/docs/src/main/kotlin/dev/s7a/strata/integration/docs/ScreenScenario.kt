package dev.s7a.strata.integration.docs

/**
 * Immutable metadata linking one complete screen to compiled source and verified frame dimensions.
 *
 * @property screen typed screen identity.
 * @property source contained compiled-source marker.
 * @property viewportMetadata exact logical frame metadata.
 */
internal data class ScreenScenario(
    val screen: DocumentedScreen,
    val source: SourceReference,
    private val viewportMetadata: ShowcaseViewport,
) {
    /**
     * Exact logical width of the generated screen image.
     */
    val viewportWidth: Int
        get() = viewportMetadata.size.width

    /**
     * Exact logical height of the generated screen image.
     */
    val viewportHeight: Int
        get() = viewportMetadata.size.height

    /**
     * Exact physical pixels per logical pixel.
     */
    val scale: Int
        get() = viewportMetadata.scale
}
