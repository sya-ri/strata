package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.Resource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Properties

/**
 * Records active default-font inputs and exact image evidence without supplying native observations to the candidate renderer.
 * Resource enumeration and bounded stream hashing run on the client thread and retain only detached strings.
 * Receipts are published only after every native, portable, and Fabric pixel comparison succeeds.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftTextReadabilityEvidence {
    /**
     * Captures font-option identity, native string widths, and every resource stack in the default font's font/texture directories.
     * All streams close synchronously and use the existing default font byte ceiling; ordinary I/O failures fail the diagnostic.
     */
    fun inputs(
        minecraft: Minecraft,
        profile: MinecraftUiProfile,
    ): Map<String, String> {
        check(minecraft.isSameThread)
        val snapshot = MinecraftProfileCacheInspection.fonts(profile)
        val options =
            MinecraftFontOptions(
                uniform = minecraft.options.forceUnicodeFont().get(),
                japaneseVariants = minecraft.options.japaneseGlyphVariants().get(),
                rightToLeft = Language.getInstance().isDefaultRightToLeft(),
            )
        check(snapshot.options == options) { "Native and portable readability font selections differ." }
        return buildMap {
            put("minecraft.version", checkNotNull(System.getProperty("strata.minecraftVersion")))
            put("font", "minecraft:default")
            put("style", "ContainerLabel")
            put("foreground.argb", Integer.toHexString(MinecraftTextReadabilityScene.FOREGROUND))
            put("background.argb", Integer.toHexString(MinecraftTextReadabilityScene.BACKGROUND))
            put("shadow", "false")
            put("font.options.uniform", options.uniform.toString())
            put("font.options.japaneseVariants", options.japaneseVariants.toString())
            put("font.options.rightToLeft", options.rightToLeft.toString())
            put("font.compatibility", snapshot.compatibility.toString())
            put("font.diagnostics", snapshot.diagnostics.toString())
            put("locale", minecraft.options.languageCode)
            put("resource.directories", "font,textures/font")
            putAll(resourceInputs(minecraft))
            MinecraftTextReadabilityScene.rows.forEachIndexed { index, row ->
                put("row.$index.text", row.text)
                put("row.$index.left", MinecraftTextReadabilityScene.LEFT.toString())
                put("row.$index.top", row.top.toString())
                put("row.$index.nativeWidth", minecraft.font.width(Component.literal(row.text)).toString())
            }
        }
    }

    /**
     * Writes detached diagnostic properties without treating their presence as an acceptance result.
     */
    fun write(
        path: Path,
        values: Map<String, String>,
    ) {
        val properties = Properties()
        values.toSortedMap().forEach { (key, value) -> properties.setProperty(key, value) }
        Files.newOutputStream(path).use { properties.store(it, "Minecraft default-font readability") }
    }

    /**
     * Binds successful exact comparisons and separately labelled headless previews to their actual PNG bytes.
     */
    fun receipt(
        output: Path,
        inputs: Map<String, String>,
    ) {
        val values = inputs.toMutableMap()
        values["status"] = "verified"
        values["verifiedAt"] = Instant.now().toString()
        values["native.fabric.headless.pixels"] = "exact"
        values["gui.scales"] = "1,2,3"
        values["viewport.width"] = MinecraftTextReadabilityScene.viewport.width.toString()
        values["viewport.height"] = MinecraftTextReadabilityScene.viewport.height.toString()
        values["preview.role"] = "headless-only-original-showcase-rerasterization"
        values["preview.guiScales"] = "2,3"
        values["preview.text.logicalSize"] = "192x88"
        values["preview.text-area.logicalSize"] = "226x80"
        for (scale in 1..3) {
            values["scale.$scale.comparedPixels"] = (MinecraftTextReadabilityScene.viewport.width * MinecraftTextReadabilityScene.viewport.height * scale * scale).toString()
        }
        val names =
            (1..3).flatMap { scale -> listOf("text-native-$scale.png", "text-headless-$scale.png", "text-fabric-$scale.png", "native-scale-$scale.properties", "fabric-scale-$scale.properties") } +
                (2..3).flatMap { scale -> listOf("showcase-text-headless-$scale.png", "showcase-text-area-headless-$scale.png") } + "inputs.properties"
        names.forEach { name -> values["file.$name.sha256"] = sha256(Files.readAllBytes(output.resolve(name))) }
        write(output.resolve("readability.properties"), values)
    }

    private fun resourceInputs(minecraft: Minecraft): Map<String, String> =
        buildMap {
            val limits = MinecraftFontLoadLimits()
            val resources =
                listOf("font", "textures/font")
                    .flatMap { directory ->
                        minecraft.resourceManager.listResourceStacks(directory) { true }.entries
                    }.sortedBy { it.key.toString() }
            check(resources.size <= limits.maxEntries) { "Readability font resource enumeration exceeded its ceiling." }
            put("resource.count", resources.size.toString())
            resources.forEachIndexed { index, (identifier, stack) ->
                put("resource.$index.id", identifier.toString())
                put("resource.$index.stackSize", stack.size.toString())
                stack.forEachIndexed { layer, resource ->
                    val (size, hash) = digest(resource, limits.maxAssetBytes)
                    put("resource.$index.$layer.sourcePack", resource.sourcePackId())
                    put("resource.$index.$layer.bytes", size.toString())
                    put("resource.$index.$layer.sha256", hash)
                }
            }
        }

    private fun digest(
        resource: Resource,
        maximumBytes: Int,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val buffer = ByteArray(8_192)
        resource.open().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                length += count
                check(length <= maximumBytes) { "A readability font resource exceeded its byte ceiling." }
                digest.update(buffer, 0, count)
            }
        }
        return length to HexFormat.of().formatHex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
