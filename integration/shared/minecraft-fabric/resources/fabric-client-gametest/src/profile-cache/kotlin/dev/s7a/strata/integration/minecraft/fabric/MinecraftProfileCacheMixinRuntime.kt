package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonParser
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.util.VersionNumber
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Verifies the effective lifecycle configuration and loaded Mixin version before native cache probes.
 *
 * Reads the current classpath and runtime directly, independently of log rotation.
 * Every failure rejects the loaded gate, including duplicate resources contributed by nested jars.
 */
internal object MinecraftProfileCacheMixinRuntime {
    /**
     * Returns detached configuration and runtime-version evidence after classpath validation.
     *
     * Call on the loaded client thread after startup has completed; all streams close before return.
     * @throws IllegalStateException when configuration uniqueness or the minimum runtime version is not established.
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
        return mapOf(
            "mixin.configurationCount" to resources.size.toString(),
            "mixin.configurationSha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            "mixin.minimumVersion" to minimum,
            "mixin.runtimeVersion" to running,
        )
    }
}
