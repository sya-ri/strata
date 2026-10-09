package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.file.Path

/**
 * Measures actual supplied shared-Fabric proof and partition code against the same immutable original commands.
 * Proof decisions are checked with an independent conservative oracle outside timing; native presentation remains separate.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class SamplingCompositionBenchmark {
    /**
     * Constructs one preparation-local proof and checks every eligible mask occurrence with the original shared budget.
     */
    @Benchmark
    public fun proof(state: ProofState): Int = state.prove()

    /**
     * Partitions the complete original frame, preserving independent visibility, sampling and output-capacity gates.
     */
    @Benchmark
    public fun partition(state: ProofState): Any = state.partition()

    /**
     * Frozen controls distinguish lazy ordinary frames, mask relationships and early or late global blockers.
     */
    public enum class Case {
        OrdinaryLarge,
        OneMaskFew,
        OneMaskMany,
        DisjointFew,
        DisjointMany,
        TouchingMany,
        OverlappingMany,
        RepeatedMany,
        EarlyBlockedMany,
        LateBlockedMany,
        ;

        /**
         * Creates the full original stream outside recurring measurement, including equal/repeated object occurrences.
         */
        public fun commands(): List<DrawCommand> {
            val count = if (this == OneMaskFew || this == OneMaskMany) 1 else 32
            val fills = if (this == OneMaskFew || this == DisjointFew) 1 else 4096
            val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x807195B3.toInt(), 0, -1))
            val prototype = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(8f, 8f, 32f, 24f), ArgbColor(0xFF00FFFF.toInt()), alphaCutoff = 1f)
            val background = DrawCommand.FillRectangle(IntRect(0, 0, 512, 256), ArgbColor(0xFF7195B3.toInt()))
            val opaque = DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(-1))
            val placements = placements(count, prototype)
            val base = listOf(background) + List(fills - 1) { opaque } + placements
            val first = DrawCommand.FillRectangle(IntRect(400, 180, 404, 184), ArgbColor(0x80472853.toInt()))
            val second = first.copy(color = ArgbColor(0x80007BBC.toInt()))
            return when (this) {
                OrdinaryLarge -> {
                    listOf(background, DrawCommand.PushClip(IntRect(0, 0, 512, 256)), DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.25f, 511.75f, 255.75f))) +
                        List(fills - 1) { opaque } + placements + prototype.copy(tint = ArgbColor(0x00123456), alphaCutoff = 0.25f) + listOf(DrawCommand.PopClip, DrawCommand.PopClip)
                }

                EarlyBlockedMany -> {
                    listOf(first) + base +
                        listOf(
                            second,
                            prototype.copy(tint = ArgbColor(0xFF123456.toInt())),
                            prototype.copy(alphaCutoff = 0.5f),
                            prototype.copy(tint = ArgbColor(-1), alphaCutoff = 0f),
                            DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(432, 180, 434, 182)),
                            DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(436, 180, 438, 182)),
                            DrawCommand.Platform(ProofPlatform, IntRect(440, 180, 442, 182)),
                        )
                }

                LateBlockedMany -> {
                    base + listOf(first, second)
                }

                else -> {
                    base
                }
            }
        }

        private fun placements(
            count: Int,
            prototype: DrawCommand.SampledImage,
        ): List<DrawCommand.SampledImage> =
            List(count) { index ->
                val stepX = if (this == TouchingMany) 24f else 48f
                val stepY = if (this == TouchingMany) 16f else 40f
                val x = 8f + index % 8 * stepX
                val y = 8f + index / 8 * stepY
                when (this) {
                    RepeatedMany -> prototype
                    OverlappingMany -> prototype.copy()
                    OrdinaryLarge -> prototype.copy(destination = FloatRect(x, y, x + 24f, y + 16f), tint = ArgbColor(-1), alphaCutoff = 0f)
                    else -> prototype.copy(destination = FloatRect(x, y, x + 24f, y + 16f))
                }
            }
    }

    /**
     * Reflects the actual Fabric implementation without starting a client or introducing a second proof engine.
     */
    @State(Scope.Thread)
    public open class ProofState {
        /**
         * Complete compiled case selection, shared by baseline and candidate.
         */
        @JvmField
        @Param("OrdinaryLarge", "OneMaskFew", "OneMaskMany", "DisjointFew", "DisjointMany", "TouchingMany", "OverlappingMany", "RepeatedMany", "EarlyBlockedMany", "LateBlockedMany")
        public var case: Case = Case.OrdinaryLarge

        private lateinit var commands: List<DrawCommand>
        private lateinit var occurrences: List<Int>
        private lateinit var proofConstructor: Constructor<*>
        private lateinit var admits: Method
        private lateinit var partitionMethod: Method

        /**
         * Resolves immutable input and actual implementation members, then verifies every possible positive decision.
         */
        @Setup(Level.Trial)
        @Suppress("StringLiteralComparison") // Reflection matches actual external JVM member names, not domain-state strings.
        public fun setup() {
            commands = case.commands()
            occurrences = commands.indices.filter { index -> (commands[index] as? DrawCommand.SampledImage)?.let { it.alphaCutoff == 1f && opaqueTint(it.tint) } == true }
            val type = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingComposition")
            proofConstructor = type.declaredConstructors.single { it.parameterCount == 1 }
            check(proofConstructor.trySetAccessible())
            admits = type.declaredMethods.single { it.name.substringBefore('$') == "admits" && it.parameterCount == 2 }
            check(admits.trySetAccessible())
            partitionMethod = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayerKt").declaredMethods.single { it.name == "partitionFabricMinecraftFrame" }
            val snapshot = work()
            check(snapshot.get("work_debits").asInt <= 8192)
            val expected = occurrences.count { oracle(commands, it) }
            check(snapshot.get("admitted").asInt <= expected)
            if (type.declaredFields.any { it.name == "classificationVisits" }) check(snapshot.get("admitted").asInt == expected)
            check(partition() is List<*>)
        }

        /**
         * Returns the real positive admission count; construction and all selected occurrences remain inside this boundary.
         */
        internal fun prove(): Int {
            val proof = proofConstructor.newInstance(commands)
            var admitted = 0
            for (occurrence in occurrences) {
                if (admits.invoke(proof, occurrence, commands[occurrence]) as Boolean) admitted += 1
            }
            return admitted
        }

        /**
         * Uses the actual complete partition entry with exact effects enabled and a fixed logical viewport.
         */
        internal fun partition(): Any = checkNotNull(partitionMethod.invoke(null, commands, IntSize(512, 256), 1, false, true))

        /**
         * Captures actual untimed decisions and available bounded-work fields without synthesizing missing legacy diagnostics.
         */
        internal fun work(): JsonObject {
            val proof = proofConstructor.newInstance(commands)
            var admitted = 0
            for (occurrence in occurrences) {
                val actual = admits.invoke(proof, occurrence, commands[occurrence]) as Boolean
                if (actual) {
                    check(oracle(commands, occurrence)) { "An admitted occurrence changed conservative composition" }
                    admitted += 1
                }
            }
            return JsonObject().apply {
                addProperty("case", case.name)
                addProperty("commands", commands.size)
                addProperty("candidate_occurrences", occurrences.size)
                addProperty("admitted", admitted)
                addProperty("work_debits", 8192 - field(proof, "remaining"))
                listOf("admissionVisits", "classificationVisits", "maskVisits", "geometryChecks").forEach { name ->
                    val diagnostic = proof.javaClass.declaredFields.firstOrNull { it.name.contentEquals(name) }
                    if (diagnostic == null) {
                        add(name, JsonNull.INSTANCE)
                    } else {
                        check(diagnostic.trySetAccessible())
                        addProperty(name, diagnostic.getInt(proof))
                    }
                }
            }
        }

        private fun field(
            value: Any,
            name: String,
        ): Int {
            val field = value.javaClass.getDeclaredField(name)
            check(field.trySetAccessible())
            return field.getInt(value)
        }
    }

    /**
     * Optional shared fixture verification and a separate actual-runtime work diagnostic, both outside timing.
     */
    public companion object {
        /**
         * Verifies the complete immutable case metadata, conservative relationships and literal opaque-mask pixels.
         */
        @JvmStatic
        public fun verifyWork() {
            check(Case.entries.size == 10)
            Case.entries.forEach { case ->
                val commands = case.commands()
                val candidates = commands.indices.filter { index -> (commands[index] as? DrawCommand.SampledImage)?.let { it.alphaCutoff == 1f && opaqueTint(it.tint) } == true }
                val admitted = candidates.count { oracle(commands, it) }
                val expected =
                    when (case) {
                        Case.OrdinaryLarge, Case.OverlappingMany, Case.RepeatedMany, Case.EarlyBlockedMany, Case.LateBlockedMany -> 0
                        Case.OneMaskFew, Case.OneMaskMany -> 1
                        else -> 32
                    }
                check(admitted == expected)
                if (case != Case.EarlyBlockedMany) {
                    val pixels = rasterizeHeadless(commands, IntSize(512, 256))
                    check(pixels.argbAt(8, 8) == if (case == Case.OrdinaryLarge) -1 else 0xFF00FFFF.toInt())
                    if (case == Case.LateBlockedMany) check(pixels.argbAt(401, 181) == 0xFF2E6DA0.toInt())
                }
            }
        }

        /**
         * Writes fresh actual proof work diagnostics; this contains no timings and cannot replace a CPU provenance receipt.
         */
        @JvmStatic
        public fun main(args: Array<String>) {
            require(args.size == 1)
            verifyWork()
            val rows = JsonArray()
            Case.entries.forEach { case ->
                val state = ProofState()
                state.case = case
                state.setup()
                rows.add(state.work())
            }
            PerformanceJson.writeNew(
                Path.of(args.single()),
                JsonObject().apply {
                    addProperty("diagnostic", "sampling-composition-work-v1")
                    addProperty("fixture_archive_sha256", ArtifactIdentity.fullCodeSource(SamplingCompositionBenchmark::class.java))
                    add(
                        "runtime",
                        LoadedArtifactMetadata
                            .capture(
                                SamplingCompositionBenchmark::class.java.classLoader,
                                mapOf("fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingComposition"),
                                setOf("fabric"),
                            ).also(LoadedArtifactMetadata::verifyComplete),
                    )
                    add("cases", rows)
                },
            )
        }

        private fun oracle(
            commands: List<DrawCommand>,
            occurrence: Int,
        ): Boolean {
            val target = (commands[occurrence] as DrawCommand.SampledImage).destination
            for (index in commands.indices) {
                if (index == occurrence) continue
                when (val other = commands[index]) {
                    is DrawCommand.FillRectangle -> {
                        if (other.color.value ushr 24 != 255) return false
                    }

                    is DrawCommand.BlitImage, is DrawCommand.BlitImagePixels, is DrawCommand.Platform -> {
                        return false
                    }

                    is DrawCommand.PushClip, is DrawCommand.PushFractionalClip, DrawCommand.PopClip -> {
                        Unit
                    }

                    is DrawCommand.SampledImage -> {
                        if (other.tint.value ushr 24 == 0) continue
                        if (other.alphaCutoff != 1f || opaqueTint(other.tint).not()) return false
                        if (maxOf(target.left, other.destination.left) < minOf(target.right, other.destination.right) && maxOf(target.top, other.destination.top) < minOf(target.bottom, other.destination.bottom)) return false
                    }
                }
            }
            return true
        }

        private fun opaqueTint(tint: ArgbColor): Boolean = tint.value ushr 24 == 255 && listOf(tint.value ushr 16 and 255, tint.value ushr 8 and 255, tint.value and 255).all { it == 0 || it == 255 }
    }

    private data object ProofPlatform : PlatformDrawCommand
}
