package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.util.VersionNumber
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Verifies the one effective lifecycle configuration and actual Mixin startup before native cache probes.
 *
 * Reads only the current classpath and client log; no state is changed and no game resource becomes a rendering input.
 * Every failure rejects the loaded gate, including duplicate resources contributed by nested jars.
 */
internal object MinecraftProfileCacheMixinRuntime {
    /**
     * Returns detached configuration and runtime-version evidence after exact classpath and startup validation.
     *
     * Call on the loaded client thread after startup has completed; all streams close before return.
     * @throws IllegalStateException when configuration uniqueness, minimum version, or clean startup is not established.
     */
    fun verify(): Map<String, String> {
        val resources = javaClass.classLoader.getResources("strata.client.mixins.json").toList()
        check(resources.size == 1) { "Expected exactly one effective lifecycle configuration: $resources" }
        val bytes = resources.single().openStream().use { it.readNBytes(4097) }
        check(bytes.size <= 4096) { "Lifecycle configuration exceeds its fixed metadata bound." }
        val configuration = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        val minimum = configuration.getAsJsonPrimitive("minVersion").asString
        val running = MixinEnvironment.getCurrentEnvironment().version
        check(VersionNumber.parse(minimum) <= VersionNumber.parse(running)) { "Mixin $running is older than required $minimum." }
        verifyStartupLog(FabricLoader.getInstance().gameDir.resolve("logs/latest.log"), VersionNumber.parse(running))
        return mapOf(
            "mixin.configurationCount" to resources.size.toString(),
            "mixin.configurationSha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            "mixin.minimumVersion" to minimum,
            "mixin.runtimeVersion" to running,
            "mixin.startupLog" to "verified",
        )
    }

    private fun verifyStartupLog(
        path: Path,
        running: VersionNumber,
    ) {
        val declared = ArrayList<VersionNumber>()
        Files.newBufferedReader(path).useLines { lines ->
            lines.forEach { line ->
                check(mixinFailure.containsMatchIn(line).not()) { "Mixin startup failure in $path: $line" }
                startupVersion.find(line)?.let { declared += VersionNumber.parse(it.groupValues[1]) }
            }
        }
        check(declared == listOf(running)) { "The current client log does not prove one matching Mixin startup: $declared" }
    }

    private val startupVersion = Regex("SpongePowered MIXIN Subsystem Version=(\\S+).*Env=CLIENT")
    private val mixinFailure = Regex("(?i)(?:\\b(?:ERROR|FATAL)\\b.*mixin|InvalidMixinException|MixinApplyError)")
}
