package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Immutable snapshots of the Minecraft GameTest receipt and its exact full-frame component renders.
 *
 * Loading verifies the fixed 26.2 environment, PNG dimensions, and every receipt hash before any generated documentation is written.
 */
internal class ShowcaseParityEvidence private constructor(
    receipt: ByteArray,
    overview: ByteArray,
    components: Map<DocumentedComponent, ByteArray>,
    componentViewports: Map<DocumentedComponent, IntSize>,
    screens: Map<DocumentedScreen, ByteArray>,
) {
    private val receiptSnapshot: ByteArray = receipt.copyOf()
    private val overviewSnapshot: ByteArray = overview.copyOf()
    private val componentSnapshots: Map<DocumentedComponent, ByteArray> =
        components.mapValues { (_, bytes) -> bytes.copyOf() }
    private val componentViewportSnapshots: Map<DocumentedComponent, IntSize> = componentViewports.toMap()
    private val screenSnapshots: Map<DocumentedScreen, ByteArray> = screens.mapValues { (_, bytes) -> bytes.copyOf() }

    /**
     * Returns the verified overview frame as a fresh byte array.
     *
     * @return independent deterministic PNG bytes.
     */
    internal fun overviewPng(): ByteArray = overviewSnapshot.copyOf()

    /**
     * Returns one verified dedicated component frame as a fresh byte array.
     *
     * @param component documented Minecraft component selecting the frame.
     * @return independent deterministic PNG bytes.
     */
    internal fun componentPng(component: DocumentedComponent): ByteArray = componentSnapshots.getValue(component).copyOf()

    /**
     * Returns the logical viewport recorded for one dedicated component frame.
     *
     * @param component documented Minecraft component selecting the receipt fields.
     * @return positive full-frame dimensions verified against the PNG header.
     */
    internal fun componentViewport(component: DocumentedComponent): IntSize = componentViewportSnapshots.getValue(component)

    /**
     * Returns one verified complete-screen image as a fresh byte array.
     *
     * @param screen typed documented use case.
     * @return independent deterministic PNG bytes.
     */
    internal fun screenPng(screen: DocumentedScreen): ByteArray = screenSnapshots.getValue(screen).copyOf()

    /**
     * Returns the exact GameTest verification receipt as a fresh byte array.
     *
     * @return independent UTF-8 receipt bytes.
     */
    internal fun receipt(): ByteArray = receiptSnapshot.copyOf()

    /**
     * Loads and verifies one Minecraft 26.2 GameTest evidence directory.
     */
    companion object {
        /**
         * Reads the fixed receipt and all typed component images without following symbolic paths.
         *
         * @param root validated GameTest parity output directory.
         * @return detached verified evidence snapshots.
         * @throws IllegalArgumentException when a file, receipt field, hash, PNG signature, or dimension differs from the locked contract.
         * @throws java.io.IOException when a filesystem read fails.
         */
        internal fun load(root: Path): ShowcaseParityEvidence {
            val receiptBytes = readRegular(root.resolve("receipt.properties"), "Minecraft parity receipt")
            val values = parseReceipt(receiptBytes)
            require(values.keys == expectedReceiptKeys) {
                "Minecraft parity receipt fields differ from the locked contract: ${values.keys}."
            }
            requireMinecraftVersion(values.getValue("minecraft.version"))
            require(values.getValue("viewport.width").toIntOrNull() == 320 && values.getValue("viewport.height").toIntOrNull() == 180) {
                "Minecraft parity receipt has the wrong full-frame viewport."
            }
            require(values.getValue("gui.scale").toIntOrNull() == 1) {
                "Minecraft parity receipt has the wrong GUI scale."
            }
            requireLocale(values.getValue("locale"))
            requireHash(values.getValue("native.fabric.headless.argb.sha256"), "full-frame pixel hash")
            requireHash(values.getValue("native.fabric.headless.scroll.argb.sha256"), "Scroll full-frame pixel hash")
            requireHash(values.getValue("native.fabric.headless.direct-join.argb.sha256"), "Direct Join full-frame pixel hash")
            requireHash(values.getValue("native.fabric.headless.container-background.argb.sha256"), "ContainerBackground full-frame pixel hash")
            requireHash(values.getValue("native.fabric.headless.slot.argb.sha256"), "Slot full-frame pixel hash")
            requireHash(values.getValue("fabric.headless.industrial.argb.sha256"), "industrial full-frame pixel hash")
            requireHash(values.getValue("native.fabric.headless.player-head.argb.sha256"), "PlayerHead full-frame pixel hash")
            requireHash(values.getValue("native.fabric.headless.social.argb.sha256"), "Social Interactions full-frame pixel hash")
            requireHash(values.getValue("fabric.headless.progress.argb.sha256"), "progress full-frame pixel hash")

            val overview = readVerifiedPng(root, "overview", values, IntSize(320, 180))
            val componentViewports =
                DocumentedComponent.entries.associateWith { component ->
                    readComponentViewport(values, component.slug)
                }
            val components =
                DocumentedComponent.entries.associateWith { component ->
                    readVerifiedPng(root, component.slug, values, componentViewports.getValue(component))
                }
            val screens =
                DocumentedScreen.entries.associateWith { screen ->
                    val expectedSize =
                        when (screen) {
                            DocumentedScreen.SocialInteractions,
                            DocumentedScreen.SynchronizedInventory,
                            -> IntSize(320, 240)

                            DocumentedScreen.IndustrialController,
                            DocumentedScreen.PowerMilestones,
                            -> IntSize(320, 180)
                        }
                    val bytes = readRegular(root.resolve("screens/${screen.slug}.png"), "Minecraft parity screen ${screen.slug}")
                    requirePngSize(bytes, expectedSize, screen.slug)
                    val expectedHash = values.getValue("screen.${screen.slug}.png.sha256")
                    requireHash(expectedHash, "${screen.slug} screen PNG hash")
                    require(sha256(bytes) == expectedHash) { "Minecraft parity screen image hash differs for ${screen.slug}." }
                    bytes
                }
            return ShowcaseParityEvidence(receiptBytes, overview, components, componentViewports, screens)
        }

        private fun requireMinecraftVersion(value: String) {
            require(MinecraftVersion.entries.any { version -> version.receiptValue == value }) {
                "Minecraft parity receipt has the wrong game version."
            }
        }

        private fun requireLocale(value: String) {
            require(ParityLocale.entries.any { locale -> locale.receiptValue == value }) {
                "Minecraft parity receipt has the wrong locale."
            }
        }

        private enum class MinecraftVersion(
            @Suppress("unused")
            val receiptValue: String,
        ) {
            Current("26.2"),
        }

        private enum class ParityLocale(
            @Suppress("unused")
            val receiptValue: String,
        ) {
            EnglishUnitedStates("en_us"),
        }

        private fun readVerifiedPng(
            root: Path,
            slug: String,
            values: Map<String, String>,
            expectedSize: IntSize,
        ): ByteArray {
            val bytes = readRegular(root.resolve("components/$slug.png"), "Minecraft parity image $slug")
            requirePngSize(bytes, expectedSize, slug)
            val expectedHash = values.getValue("component.$slug.png.sha256")
            requireHash(expectedHash, "$slug PNG hash")
            require(sha256(bytes) == expectedHash) { "Minecraft parity image hash differs for $slug." }
            return bytes
        }

        private fun readComponentViewport(
            values: Map<String, String>,
            slug: String,
        ): IntSize {
            val fieldPrefix = "component.$slug"
            val width = values.getValue("$fieldPrefix.viewport.width").toIntOrNull()
            val height = values.getValue("$fieldPrefix.viewport.height").toIntOrNull()
            require(width != null && 0 < width && height != null && 0 < height) {
                "Minecraft parity receipt has an invalid full-frame viewport for $slug."
            }
            requireHash(
                values.getValue("$fieldPrefix.fabric.headless.argb.sha256"),
                "$slug Fabric/headless full-frame pixel hash",
            )
            return IntSize(width, height)
        }

        private fun readRegular(
            path: Path,
            label: String,
        ): ByteArray {
            ShowcasePaths.requireSafeSegments(path, label)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "$label is missing or not regular: $path" }
            return Files.readAllBytes(path)
        }

        private fun parseReceipt(bytes: ByteArray): Map<String, String> {
            val text =
                try {
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (failure: CharacterCodingException) {
                    throw IllegalArgumentException("Minecraft parity receipt is not valid UTF-8.", failure)
                }
            require(text.endsWith('\n') && text.dropLast(1).endsWith('\n').not() && text.contains('\r').not()) {
                "Minecraft parity receipt must use LF and one terminal line ending."
            }
            val values = LinkedHashMap<String, String>()
            text.dropLast(1).lines().forEach { line ->
                val separator = line.indexOf('=')
                require(0 < separator && separator + 1 < line.length) { "Minecraft parity receipt contains a malformed line." }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(values.put(key, value) == null) { "Minecraft parity receipt contains duplicate field $key." }
            }
            return values
        }

        private fun requirePngSize(
            bytes: ByteArray,
            expected: IntSize,
            slug: String,
        ) {
            require(24 <= bytes.size && bytes.copyOfRange(0, pngSignature.size).contentEquals(pngSignature)) {
                "Minecraft parity image is not a PNG for $slug."
            }
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.getInt(16) == expected.width && buffer.getInt(20) == expected.height) {
                "Minecraft parity image has the wrong dimensions for $slug."
            }
        }

        private fun requireHash(
            value: String,
            label: String,
        ) {
            require(hashPattern.matches(value)) { "Minecraft parity receipt contains an invalid $label." }
        }

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private val hashPattern = Regex("[0-9a-f]{64}")
        private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val expectedReceiptKeys: Set<String> =
            buildSet {
                add("minecraft.version")
                add("viewport.width")
                add("viewport.height")
                add("gui.scale")
                add("locale")
                add("native.fabric.headless.argb.sha256")
                add("native.fabric.headless.scroll.argb.sha256")
                add("native.fabric.headless.direct-join.argb.sha256")
                add("native.fabric.headless.container-background.argb.sha256")
                add("native.fabric.headless.slot.argb.sha256")
                add("fabric.headless.industrial.argb.sha256")
                add("native.fabric.headless.player-head.argb.sha256")
                add("native.fabric.headless.social.argb.sha256")
                add("fabric.headless.progress.argb.sha256")
                add("component.overview.png.sha256")
                DocumentedComponent.entries.forEach { component ->
                    val prefix = "component.${component.slug}"
                    add("$prefix.viewport.width")
                    add("$prefix.viewport.height")
                    add("$prefix.fabric.headless.argb.sha256")
                    add("$prefix.png.sha256")
                }
                DocumentedScreen.entries.forEach { screen -> add("screen.${screen.slug}.png.sha256") }
            }
    }
}
