package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO

/**
 * Qualifies controls 29/30 from actual loaded device, screen, GUI-consumer and pixel receipts before timing.
 * One immutable manifest registers both variants, their runtime archives, two probe reports and eight screenshots each.
 * The manifest names inputs; assertions are read from the shipped probes rather than supplied as independent pass flags.
 * Directory origins, unsupported exact adapters, partial cases and missing probe/archive/collector identity fail qualification.
 */
public object SamplingCoverageLoadedControls {
    /**
     * Reads the registered sampling-loaded-controls manifest and checks both variants against frozen input hashes.
     * Each variant declares runtimeArchiveInput, deviceInput, presenterInput, screenshots keyed by FrameOnly,
     * and evidenceInputs entries with label and sha256; every label resolves to an immutable collector input.
     * Device and presenter receipts must share actual loaded map identity and identical compiled probes/collector across variants.
     * The current measured archive must match exactly one qualified variant before the shared collector may run.
     */
    public fun verify(inputs: Map<String, Path>) {
        val bundle = checkNotNull(inputs["sampling-loaded-controls"]) { "Controls 29/30 require actual paired loaded receipts before timing." }
        val manifest = read(bundle)
        for ((field, label) in mapOf("fixturesSha256" to "sampling-fixtures", "matrixSha256" to "sampling-matrix", "controlsSha256" to "sampling-controls", "sourceSha256" to "sampling-source")) {
            check(manifest.get(field).asString == ArtifactIdentity.file(checkNotNull(inputs[label])))
        }
        val proofs = manifest.getAsJsonArray("variants").map { it.asJsonObject }
        check(proofs.size == 2 && proofs.map { it.get("runtimeArchiveSha256").asString }.distinct().size == 2)
        val representative = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingMap", false, javaClass.classLoader)
        val matching = proofs.single { it.get("runtimeArchiveSha256").asString == ArtifactIdentity.fullCodeSource(representative) }
        check(matching.get("samplingMapClassSha256").asString == LoadedArtifactMetadata.captureClassHashes(listOf(representative)).get(representative.name).asString)
        val pairs = proofs.map { proof -> verifyVariant(inputs, proof, representative.name) }
        check(pairs[0].first.get("probeClassHashes") == pairs[1].first.get("probeClassHashes"))
        check(pairs[0].second.get("probeClassHashes") == pairs[1].second.get("probeClassHashes"))
        check(pairs[0].second.get("backend") == pairs[1].second.get("backend"))
    }

    private fun verifyVariant(
        inputs: Map<String, Path>,
        proof: JsonObject,
        mapClass: String,
    ): Pair<JsonObject, JsonObject> {
        check(ArtifactIdentity.file(checkNotNull(inputs[proof.get("runtimeArchiveInput").asString])) == proof.get("runtimeArchiveSha256").asString)
        val entries = proof.getAsJsonArray("evidenceInputs").map { it.asJsonObject }
        val evidence = entries.associate { it.get("label").asString to it.get("sha256").asString }
        check(entries.size == evidence.size && evidence.size == 10)
        for ((label, sha256) in evidence) check(ArtifactIdentity.file(checkNotNull(inputs[label])) == sha256)
        val deviceLabel = proof.get("deviceInput").asString
        val presenterLabel = proof.get("presenterInput").asString
        check(deviceLabel != presenterLabel && deviceLabel in evidence && presenterLabel in evidence)
        val device = read(checkNotNull(inputs[deviceLabel]))
        val presenter = read(checkNotNull(inputs[presenterLabel]))
        val screenshots = proof.getAsJsonObject("screenshots")
        val screenshotLabels = screenshots.entrySet().map { it.value.asString }
        check(screenshotLabels.distinct().size == 8 && evidence.keys == screenshotLabels.toSet() + deviceLabel + presenterLabel)
        for (report in listOf(device, presenter)) {
            check(report.get("runtimeArchiveSha256") == proof.get("runtimeArchiveSha256"))
            check(report.get("samplingMapClassSha256") == proof.get("samplingMapClassSha256"))
            verifyOrigin(report, mapClass)
            check(report.getAsJsonObject("probeClassHashes").size() != 0)
            check(report.get("collector_identity") == PerformanceJson.collectorIdentity())
        }
        verifyDevice(device)
        verifyPresenter(inputs, evidence, screenshots, presenter)
        return device to presenter
    }

    private fun verifyOrigin(
        report: JsonObject,
        mapClass: String,
    ) {
        val loaded = report.getAsJsonObject("actualLoadedRuntime")
        LoadedArtifactMetadata.verifyComplete(loaded)
        val modules = loaded.getAsJsonArray("modules").map { it.asJsonObject }
        check(modules.size == 2 && modules.map { it.get("representativeClass").asString }.distinct().size == 2)
        val module = modules.single { it.get("representativeClass").asString == mapClass }
        check(module.getAsJsonObject("codeSource").get("sha256") == report.get("runtimeArchiveSha256"))
        check(module.getAsJsonObject("classResource").get("sha256") == report.get("samplingMapClassSha256"))
        for (entry in modules) {
            check(
                entry.has("classTree") &&
                    entry
                        .getAsJsonObject("classTree")
                        .get("sha256")
                        .isJsonNull
                        .not(),
            )
        }
    }

    private fun verifyDevice(device: JsonObject) {
        check(device.get("fullLoadedControlsPassed").asBoolean.not())
        requireAssertions(device.getAsJsonObject("observations"), setOf("sourceIdentityVsEqualPixels", "sourceReplacement", "ownerIsolation", "pinnedThroughPreparation", "controlledQueueMarkerRetains", "gatedRealNativeFenceRetains", "acquisitionCallbackFailure", "failureAfterActualUpload", "primaryFailurePreserved", "deviceTerminalRelease", "zeroRetainedOwners", "zeroRetainedSources", "nativeResourcesDestroyed"))
        check(device.get("actualNativeUploads").asInt == 3)
        check(device.get("sourceWidth").asInt == 64 && device.get("sourceHeight").asInt == 48)
        check(device.get("sourcePixelsSha256").asString == sourcePixelsSha256())
        check(device.get("retainedResourcesAfter").asInt == 0 && device.get("retainedBytesAfter").asLong == 0L)
    }

    private fun verifyPresenter(
        inputs: Map<String, Path>,
        evidence: Map<String, String>,
        screenshots: JsonObject,
        presenter: JsonObject,
    ) {
        check(presenter.get("presenterAssertionsPassed").asBoolean)
        val cases = presenter.getAsJsonArray("cases").map { it.asJsonObject }
        val inventory = cases.associateBy { SamplingCoverageFixture.FrameOnly.valueOf(it.get("case").asString) }
        check(cases.size == SamplingCoverageFixture.FrameOnly.entries.size && inventory.keys == SamplingCoverageFixture.FrameOnly.entries.toSet())
        check(screenshots.keySet() == inventory.keys.map { it.name }.toSet())
        for ((case, receipt) in inventory) verifyCase(inputs, evidence, screenshots.get(case.name).asString, case, receipt)
        val clean = checkNotNull(inventory[SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity])
        requireAssertions(clean.getAsJsonObject("lifecycle"), setOf("sourceReplacement", "distinctEqualPixelSource", "detachReattach", "preparationFailure", "primaryFailurePreserved", "terminalScreenRejected", "zeroRetainedOwners", "zeroRetainedSources"))
        val lifetime = presenter.getAsJsonObject("lifetime")
        check(lifetime.get("actualGuiConsumerCompleted").asBoolean)
        check(lifetime.get("sourceRetainedAfterCloseBeforeConsumer").asInt == 1)
        check(lifetime.get("zeroRetainedOwners").asBoolean && lifetime.get("zeroRetainedSources").asBoolean)
    }

    private fun verifyCase(
        inputs: Map<String, Path>,
        evidence: Map<String, String>,
        label: String,
        case: SamplingCoverageFixture.FrameOnly,
        receipt: JsonObject,
    ) {
        val clean = case == SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity
        val exact = clean || case == SamplingCoverageFixture.FrameOnly.ChangedListSameCommands || case == SamplingCoverageFixture.FrameOnly.UnchangedMetadataEquivalentOutput
        val width = if (case == SamplingCoverageFixture.FrameOnly.ExactBudgetFallback) 4096 else 640
        val height = if (case == SamplingCoverageFixture.FrameOnly.ExactBudgetFallback) 4096 else 480
        check(receipt.get("actualNewMaps").asInt == if (exact && clean.not()) 1 else 0)
        check(receipt.get("returnedMaps").asInt == if (exact) 1 else 0)
        check(receipt.get("capacityFallback").asLong == if (case == SamplingCoverageFixture.FrameOnly.ExactBudgetFallback) 1L else 0L)
        check(receipt.get("sameCommandIdentity").asBoolean == clean && receipt.get("samePreparedInputs").asBoolean == clean)
        check(receipt.get("preparationDelta").asLong == if (clean) 0L else 1L)
        check(receipt.get("commandValuesEqual").asBoolean)
        if (case == SamplingCoverageFixture.FrameOnly.ChangedListSameCommands) check(receipt.get("sameCommandElements").asBoolean)
        if (case == SamplingCoverageFixture.FrameOnly.UnchangedMetadataEquivalentOutput) check(receipt.get("differentCommandElement").asBoolean)
        check(receipt.get("oldMapsImmutable").asBoolean && receipt.get("metadataEqual").asBoolean)
        check(receipt.get("sourcePixelsSha256").asString == sourcePixelsSha256())
        verifyScreenshot(inputs, evidence, label, receipt, IntSize(width, height))
    }

    /**
     * Checks the complete physical viewport and its independently pinned screenshot.
     */
    private fun verifyScreenshot(
        inputs: Map<String, Path>,
        evidence: Map<String, String>,
        label: String,
        receipt: JsonObject,
        size: IntSize,
    ) {
        check(receipt.get("viewportWidth").asInt == size.width && receipt.get("viewportHeight").asInt == size.height && receipt.get("density").asInt == 1)
        check(receipt.get("nativePixelsCompared").asLong == size.width.toLong() * size.height)
        val path = checkNotNull(inputs[label])
        check(label in evidence && evidence[label] == receipt.get("screenshotSha256").asString)
        check(path.fileName.toString() == receipt.get("screenshot").asString)
        val screenshot = checkNotNull(ImageIO.read(path.toFile()))
        check(screenshot.width == size.width && screenshot.height == size.height)
    }

    private fun sourcePixelsSha256(): String {
        val pixels = SamplingCoverageFixture.image.copyArgb()
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).also { it.asIntBuffer().put(pixels) }.array()
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun requireAssertions(
        assertions: JsonObject,
        required: Set<String>,
    ) {
        check(assertions.keySet() == required && required.all { assertions.get(it).asBoolean })
    }

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
}
