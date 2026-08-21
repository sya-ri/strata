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
    internal val stagingRoot: Path,
    receipt: ByteArray,
) {
    internal val sections: List<Section> = sections.toList()
    private val receiptSnapshot: ByteArray = receipt.copyOf()

    /**
     * Returns the Minecraft parity receipt as fresh bytes.
     *
     * @return independent verified receipt bytes.
     */
    internal fun receipt(): ByteArray = receiptSnapshot.copyOf()

    /**
     * Markdown containing the overview and every generated component section.
     */
    internal val componentsMarkdown: String = ShowcaseMarkdown.components(overview, this.sections)

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
}
