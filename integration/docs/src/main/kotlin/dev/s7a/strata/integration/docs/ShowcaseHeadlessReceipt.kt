package dev.s7a.strata.integration.docs

import java.nio.charset.StandardCharsets

/**
 * Produces deterministic metadata for fresh headless frames and the explicit native inventory exception.
 *
 * Receipts contain only logical input names and hashes, never absolute paths, timestamps, or claims that a native parity gate ran.
 * Inputs are consumed synchronously; no renderer, asset source, image buffer, or caller-owned map is retained.
 */
internal object ShowcaseHeadlessReceipt {
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val assetKeyPattern = Regex("[a-z][A-Za-z0-9_./-]*")

    /**
     * Builds the canonical UTF-8 receipt after every catalog frame has been prepared.
     *
     * @param assetHashes hashes keyed by stable logical asset identities, without a .sha256 suffix.
     * @param frames exact overview, component, and screen receipts keyed by their generated receipt prefixes.
     * @param inventoryProofSha256 hash of the explicitly verified native inventory receipt.
     * @return fresh LF-terminated bytes ordered independently of input map iteration.
     * @throws IllegalArgumentException when hashes, logical keys, frame coverage, or image provenance violate the catalog contract.
     */
    internal fun create(
        assetHashes: Map<String, String>,
        frames: Map<String, ShowcaseFrameReceipt>,
        inventoryProofSha256: String,
    ): ByteArray {
        val origins = expectedOrigins()
        require(frames.keys == origins.keys) { "Headless receipt frames differ from the complete showcase catalog." }
        require(assetHashes.isNotEmpty()) { "Headless receipt requires explicit asset hashes." }
        require(hashPattern.matches(inventoryProofSha256)) { "Native inventory receipt hash is invalid." }
        val fields = sortedMapOf("format.version" to "1", "generator" to "headless", "minecraft.version" to "26.2", "locale" to "en_us")
        assetHashes.forEach { (key, hash) ->
            require(assetKeyPattern.matches(key) && hashPattern.matches(hash)) { "Headless receipt asset identity or hash is invalid: $key" }
            fields["asset.$key.sha256"] = hash
        }
        frames.forEach { (prefix, frame) ->
            require(frame.origin == origins.getValue(prefix)) { "Showcase image origin differs from the catalog contract: $prefix" }
            appendFrame(fields, prefix, frame)
        }
        fields["screen.${DocumentedScreen.SynchronizedInventory.slug}.evidence.sha256"] = inventoryProofSha256
        return fields.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }.toByteArray(StandardCharsets.UTF_8)
    }

    private fun appendFrame(
        fields: MutableMap<String, String>,
        prefix: String,
        frame: ShowcaseFrameReceipt,
    ) {
        fields["$prefix.origin"] = frame.origin.receiptValue
        fields["$prefix.source.sha256"] = frame.sourceSha256
        fields["$prefix.viewport.width"] =
            frame.viewport.size.width
                .toString()
        fields["$prefix.viewport.height"] =
            frame.viewport.size.height
                .toString()
        fields["$prefix.gui.scale"] = frame.viewport.scale.toString()
        fields["$prefix.physical.width"] =
            frame.viewport.physicalSize.width
                .toString()
        fields["$prefix.physical.height"] =
            frame.viewport.physicalSize.height
                .toString()
        fields["$prefix.png.sha256"] = frame.pngSha256
    }

    private fun expectedOrigins(): Map<String, ShowcaseFrameReceipt.Origin> =
        buildMap {
            put("overview", ShowcaseFrameReceipt.Origin.Headless)
            DocumentedComponent.entries.forEach { component -> put("component.${component.slug}", ShowcaseFrameReceipt.Origin.Headless) }
            DocumentedScreen.entries.forEach { screen ->
                val origin =
                    if (screen == DocumentedScreen.SynchronizedInventory) ShowcaseFrameReceipt.Origin.LoadedServerFabric else ShowcaseFrameReceipt.Origin.Headless
                put("screen.${screen.slug}", origin)
            }
        }
}
