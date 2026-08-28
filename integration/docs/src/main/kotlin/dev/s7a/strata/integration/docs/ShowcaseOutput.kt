package dev.s7a.strata.integration.docs

import java.nio.file.Path

/**
 * Holds fully rendered showcase output until the generator serializes its isolated staging directory.
 *
 * The result owns immutable byte snapshots and does not retain mutable runtime trees.
 */
internal class ShowcaseOutput internal constructor(
    internal val overview: Overview,
    sections: List<Section>,
    screens: List<Screen>,
    internal val stagingRoot: Path,
    receipt: ByteArray,
) {
    internal val sections: List<Section> = sections.toList()
    internal val screens: List<Screen> = screens.toList()
    private val receiptSnapshot: ByteArray = receipt.copyOf()

    /**
     * Returns fresh deterministic headless input and image metadata, including explicit native inventory provenance.
     *
     * @return independent receipt bytes that do not claim a native parity gate ran during generation.
     */
    internal fun receipt(): ByteArray = receiptSnapshot.copyOf()

    /**
     * Markdown containing the overview and every generated component section.
     */
    internal val componentsMarkdown: String = ShowcaseMarkdown.components(overview, this.sections, this.screens)

    /**
     * Markdown inserted between the manually maintained root README anchors.
     */
    internal val rootReadmeRegion: String = ShowcaseMarkdown.rootReadme(overview)

    /**
     * A rendered overview image and its extracted source.
     */
    internal class Overview internal constructor(
        internal val source: String,
        internal val tree: String,
        png: ByteArray,
    ) {
        private val pngSnapshot: ByteArray = png.copyOf()

        /**
         * Returns a fresh PNG byte snapshot.
         *
         * @return independent PNG bytes.
         */
        internal fun png(): ByteArray = pngSnapshot.copyOf()
    }

    /**
     * A rendered component section and its extracted source-backed Markdown.
     */
    internal class Section internal constructor(
        component: DocumentedComponent,
        internal val section: String,
        png: ByteArray,
    ) {
        private val pngSnapshot: ByteArray = png.copyOf()
        internal val slug: String = component.slug
        internal val title: String = component.apiMethodName

        /**
         * Returns a fresh PNG byte snapshot.
         *
         * @return independent PNG bytes.
         */
        internal fun png(): ByteArray = pngSnapshot.copyOf()
    }

    /**
     * A verified complete-screen section and its extracted source-backed Markdown.
     */
    internal class Screen internal constructor(
        screen: DocumentedScreen,
        internal val section: String,
        png: ByteArray,
    ) {
        private val pngSnapshot: ByteArray = png.copyOf()
        internal val slug: String = "screen-${screen.slug}"
        internal val title: String = screen.title

        /**
         * Returns a fresh complete-screen PNG byte snapshot.
         *
         * @return independent PNG bytes.
         */
        internal fun png(): ByteArray = pngSnapshot.copyOf()
    }
}
