package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Supplemental owner-thread CPU/allocation distributions for the same 47 operation boundaries.
 * The shared testkit owns sampling and every clock/counter; JMH's 94 formal rows remain separate.
 * Setup and teardown receive independent untimed-for-JMH reports rather than entering operation intervals.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object IndexedFontCpuEvidence {
    /**
     * Accepts a new output file and independent repetition index using the same common input properties as JMH.
     * Actual loaded runtime/fixture/input identities are captured and rechecked before publishing success.
     */
    @JvmStatic
    @Suppress("LongMethod") // One explicit complete matrix keeps the supplemental evidence boundary reviewable.
    public fun main(args: Array<String>) {
        require(args.size == 2)
        val output = Path.of(args[0])
        require(Files.exists(output).not())
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        val representatives =
            mapOf(
                "api" to "dev.s7a.strata.component.UiScope",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
            )
        val loader = ClassLoader.getSystemClassLoader()
        val runtime = LoadedArtifactMetadata.capture(loader, representatives, representatives.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtureClasses =
            listOf(
                IndexedFontAssetBenchmark::class.java,
                IndexedFontReuseBenchmark::class.java,
                IndexedFontInput::class.java,
                IndexedFontAssetFiles::class.java,
                IndexedFontAssetControls::class.java,
                IndexedFontCpuEvidence::class.java,
                PortableTextBenchmark::class.java,
                PortableTextWorkEvidence::class.java,
                ComponentFontAssets::class.java,
                ComponentProfile::class.java,
                FontPerformanceAssets::class.java,
                Class.forName("dev.s7a.strata.integration.docs.FontResourceExampleKt", false, loader),
            )
        val fixtures = LoadedArtifactMetadata.captureClassHashes(fixtureClasses)
        val inputs =
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
                JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.fixtureInputs")))) +
                mapOf("cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))
        val hashes = inputs.mapValues { (_, path) -> ArtifactIdentity.file(path) }
        IndexedFontAssetControls.verify()
        val phases = JsonArray()
        val lifetimes = JsonArray()
        for (input in IndexedFontInput.entries) {
            val state =
                IndexedFontAssetBenchmark.Index().apply { this.input = input }
            lifetime(lifetimes, "cold:$input:setup") { state.setup() }
            try {
                val benchmark = IndexedFontAssetBenchmark()
                phases.add(measure("constructPaths:$input") { benchmark.constructPaths(state) })
                phases.add(measure("constructRead:$input") { benchmark.constructRead(state) ?: Unit })
                phases.add(measure("loadExample:$input") { benchmark.loadExample(state) })
            } finally {
                lifetime(lifetimes, "cold:$input:teardown") { state.close() }
            }
        }
        for (input in listOf(IndexedFontInput.Distinct1, IndexedFontInput.Shared1, IndexedFontInput.Distinct4096, IndexedFontInput.Shared4096)) {
            val state =
                IndexedFontReuseBenchmark.Index().apply { this.input = input }
            lifetime(lifetimes, "reuse:$input:setup") { state.setup() }
            try {
                val benchmark = IndexedFontReuseBenchmark()
                phases.add(measure("reusedPaths:$input") { benchmark.reusedPaths(state) })
                phases.add(measure("reusedRead:$input") { benchmark.reusedRead(state) ?: Unit })
            } finally {
                lifetime(lifetimes, "reuse:$input:teardown") { state.close() }
            }
        }
        for (face in PortableTextBenchmark.Face.entries) {
            for (density in listOf(1, 4)) {
                portable(phases, lifetimes, face, density)
            }
        }
        check(phases.size() == 47)
        check(runtime == LoadedArtifactMetadata.capture(loader, representatives, representatives.keys))
        check(fixtures == LoadedArtifactMetadata.captureClassHashes(fixtureClasses))
        check(hashes == inputs.mapValues { (_, path) -> ArtifactIdentity.file(path) })
        PerformanceJson.writeNew(
            output,
            JsonObject().apply {
                addProperty("contract", "indexed-font-supplemental-cpu-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                addProperty("warmup", 30)
                addProperty("samples", 60)
                addProperty("controls", 32)
                addProperty("gpu_upload_native_timing", "N/A: isolated offline source validation")
                add("runtime_metadata", runtime)
                add("fixture_hashes", fixtures)
                add("inputs", JsonObject().apply { hashes.forEach { (label, hash) -> addProperty(label, hash) } })
                add("phases", phases)
                add("setup_teardown", lifetimes)
            },
        )
    }

    private fun measure(
        name: String,
        operation: () -> Any,
    ): JsonObject = JvmPerformanceRunner.measure(name, operation = { operation() }).evidence

    private fun lifetime(
        reports: JsonArray,
        name: String,
        operation: () -> Unit,
    ) {
        reports.add(JvmPerformanceRunner.measure(name, PerformancePlan(warmup = 0, samples = 1), operation = { operation() }).evidence)
    }

    private fun portable(
        phases: JsonArray,
        lifetimes: JsonArray,
        face: PortableTextBenchmark.Face,
        density: Int,
    ) {
        val state =
            PortableTextBenchmark.TextPixels().apply {
                this.face = face
                this.density = density
            }
        lifetime(lifetimes, "portable:$face:$density:setup") { state.setup() }
        val benchmark = PortableTextBenchmark()
        phases.add(measure("extractGlyphs:$face:$density") { benchmark.extractGlyphs(state) })
        phases.add(measure("composePreparedGlyphs:$face:$density") { benchmark.composePreparedGlyphs(state) })
        // The unchanged fixture retains only immutable detached inputs; hosts close inside extractGlyphs.
    }
}
