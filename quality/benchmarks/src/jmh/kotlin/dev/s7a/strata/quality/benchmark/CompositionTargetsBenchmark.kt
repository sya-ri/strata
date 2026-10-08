package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Measures actual Fabric CPU-input, immutable-match and target-reservation preparation with supplied runtime archives.
 * Fresh and replacement boundaries include the same reflection adapter on both variants; fixture sources and checks are outside timing.
 * The replacement boundary retains the actual caller's same-list shortcut and otherwise supplies only its immediately previous inputs.
 * Native allocation, pass submission and GUI consumption belong to the separate native corpus; these CPU samples establish no GPU or FPS improvement.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class CompositionTargetsBenchmark {
    /**
     * Prepares a cold actual frame with no previous CPU inputs.
     */
    @Benchmark
    public fun fresh(state: Scene): Any = state.fresh()

    /**
     * Alternates prebuilt original revisions through actual partitioning and current-input replacement.
     */
    @Benchmark
    public fun replacement(state: Scene): Any = state.replace()

    /**
     * Complete stationary/rebuilt/local/geometry/control cases, with exact original Float coordinates preserved.
     */
    public enum class Case(
        public val extent: IntSize = IntSize(1920, 1080),
        public val static: Boolean = false,
        public val sourceExtent: Int = 128,
    ) {
        CleanLarge(static = true),
        DenseOddLarge,
        DenseEvenLarge,
        SparseLarge,
        ScrollLarge,
        NoOpHeavyLarge,
        CutoffHeavyLarge,
        ActivePassSwapLarge,
        OneDirtyLarge,
        ReplacementLarge,
        SourceChurnLarge,
        MixedPassesLarge,
        MixedSizesLarge(IntSize(1903, 1067)),
        SingleTileControl(IntSize(64, 64)),
        SmallSourceFallback(IntSize(64, 64), sourceExtent = 16),
        ;
    }

    /**
     * One thread's two immutable source/revision lists and bounded current actual inputs; no game or native device is started.
     */
    @State(Scope.Thread)
    @Suppress("TooManyFunctions") // One complete benchmark state owns source, current input and untimed parity/reservation observations.
    public open class Scene {
        /**
         * Every case participates in both compiled boundaries on both supplied runtimes.
         */
        @JvmField
        @Param
        public var case: Case = Case.SingleTileControl

        private lateinit var originals: List<List<DrawCommand>>
        private lateinit var constructor: Constructor<*>
        private lateinit var partition: Method
        private lateinit var portable: Method
        private lateinit var composition: Method
        private lateinit var indices: Method
        private lateinit var factors: Method
        private lateinit var sources: Method
        private lateinit var rasterize: Method
        private lateinit var origin: Method
        private var prepare: Method? = null
        private var axisEntries: Method? = null
        private var companion: Any? = null
        private lateinit var matchImages: Method
        private lateinit var reservationSize: Method
        private var targetPlan: Method? = null
        private var targetCompanion: Any? = null
        private var previousImages: List<*>? = null
        private var previousReservations: List<IntSize> = emptyList()
        private var lastPlan: Any? = null
        private var lastReservations: List<IntSize> = emptyList()
        private var current: Any? = null
        private var committed: List<DrawCommand>? = null
        private var revision = 0

        /**
         * Resolves actual archive members, prepares all source bytes, and verifies cold/rebuilt/current ownership outside samples.
         */
        @Setup(Level.Trial)
        @Suppress("LongMethod", "StringLiteralComparison") // Resolve actual external ABI names and verify original pixels outside all measured operations.
        public fun setup() {
            val loader = javaClass.classLoader
            val type = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs", true, loader)
            constructor = type.declaredConstructors.single { it.parameterCount == 6 }
            check(constructor.trySetAccessible())
            portable = getter(type, "getPortable")
            val portableType = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableImage", true, loader)
            composition = getter(portableType, "getComposition")
            reservationSize = getter(portableType, "getReservationSize")
            matchImages = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableReuseKt", true, loader).declaredMethods.single { it.name.startsWith("matchFabricMinecraftPortableImages") && it.parameterCount == 2 }
            val planType =
                try {
                    Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCompositionTargetPlan", true, loader)
                } catch (_: ClassNotFoundException) {
                    null
                }
            targetCompanion = planType?.declaredFields?.single { it.name == "Companion" }?.get(null)
            targetPlan = targetCompanion?.javaClass?.declaredMethods?.single { it.name.startsWith("create") && it.parameterCount == 3 }

            origin = getter(portableType, "getOrigin")
            rasterize = portableType.declaredMethods.single { it.name.startsWith("rasterize") && it.parameterCount == 0 }
            val mapType = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCompositionMap", true, loader)
            indices = getter(mapType, "getIndices")
            factors = getter(mapType, "getFactors")
            sources = getter(mapType, "getSources")
            axisEntries = mapType.declaredMethods.singleOrNull { it.name.startsWith("getAxisEntriesWritten") && it.parameterCount == 0 }
            partition = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayerKt", true, loader).declaredMethods.single { it.name.startsWith("partitionFabricMinecraftFrame") && it.parameterCount == 5 }
            companion = type.declaredFields.firstOrNull { it.name == "Companion" }?.get(null)
            prepare = companion?.javaClass?.declaredMethods?.singleOrNull { it.name.startsWith("prepare") && it.parameterCount == 6 }
            originals = prepareSources()
            current = prepare(originals[0], null)
            plan(checkNotNull(current), false)
            committed = originals[0]
            verify(checkNotNull(current), originals[0])
            val saved = maps(checkNotNull(current)).map { map -> (indices.invoke(map) as DrawImage) to (indices.invoke(map) as DrawImage).copyArgb() }
            verify(replace(), originals[revision])
            verify(replace(), originals[revision])
            saved.forEach { (image, pixels) -> check(image.copyArgb().contentEquals(pixels)) }
            verify(fresh(), originals[0])
        }

        /**
         * Includes actual cold partitioning, metadata/source-request preparation and the actual target reservation plan.
         */
        public fun fresh(): Any {
            val inputs = prepare(originals[0], null)
            plan(inputs, false)
            return inputs
        }

        /**
         * Uses the real presenter's identity shortcut, then the variant's actual CPU preparation entry.
         */
        public fun replace(): Any {
            revision = 1 - revision
            val commands = originals[revision]
            if (commands === committed) return checkNotNull(current)
            val next = prepare(commands, current)
            plan(next, true)
            current = next
            committed = commands
            return next
        }

        /**
         * Drops the fixture's single current owner and both immutable source/revision lists.
         */
        @TearDown(Level.Trial)
        public fun close() {
            current = null
            committed = null
            originals = emptyList()
            companion = null
            axisEntries = null
            targetCompanion = null
            targetPlan = null
            previousImages = null
            previousReservations = emptyList()
            lastPlan = null
            lastReservations = emptyList()
        }

        /**
         * Records actual metadata identity/area changes outside timing, without inventing allocation or factor computation counts.
         */
        internal fun work(): JsonObject {
            val beforeInputs = checkNotNull(current)
            val before = maps(beforeInputs)
            val oldMaps = identities(before)
            val oldIndices = identities(before.map { indices.invoke(it) })
            val oldFactors = identities(before.map { factors.invoke(it) })
            val next = replace()
            val maps = maps(next)
            val newMaps = maps.filter { (it in oldMaps).not() }
            val indexImages = maps.map { indices.invoke(it) as DrawImage }
            val factorImages = maps.map { factors.invoke(it) as DrawImage }
            val unique = identities(factorImages)
            val freshIndices = identities(indexImages.filter { (it in oldIndices).not() })
            val freshFactors = identities(factorImages.filter { (it in oldFactors).not() })
            val planned = lastPlan?.let { plan -> (getter(plan.javaClass, "getReservations").invoke(plan) as List<*>).map { it as IntSize } } ?: lastReservations.toList()
            val sharedShapes = lastPlan?.let { getter(it.javaClass, "getShapes").invoke(it) as Set<*> }?.size ?: 0
            val cold = maps(fresh())
            return JsonObject().apply {
                addProperty("case", case.name)
                addProperty("actual_shared_shape_plan_count", sharedShapes)
                addProperty("actual_rounded_reservation_bytes", planned.sumOf { it.width.toLong() * it.height * 4L })
                addProperty("commands", originals[revision].size)
                addProperty("maps", maps.size)
                addProperty("new_maps", newMaps.size)
                addProperty("new_axis_entries_written", axisEntries?.let { method -> newMaps.sumOf { method.invoke(it) as Long } })
                addProperty("factor_images", unique.size)
                addProperty("factor_pixels", pixels(unique))
                addProperty("new_index_images", freshIndices.size)
                addProperty("new_index_pixels", pixels(freshIndices))
                addProperty("new_factor_images", freshFactors.size)
                addProperty("new_factor_pixels", pixels(freshFactors))
                addProperty("cold_maps", cold.size)
                addProperty("cold_factor_images", identities(cold.map { factors.invoke(it) }).size)
                addProperty("retained_same_inputs", beforeInputs === next)
            }
        }

        /**
         * Creates both immutable original revisions outside timing, also usable when ordinary JVM checks have no Fabric archive.
         */
        internal fun prepareSources(): List<List<DrawCommand>> {
            val images = List(2) { index -> source(case.sourceExtent, if (case == Case.SourceChurnLarge) index else 0) }
            val first = commands(images[0], 0)
            return listOf(first, if (case.static) first else commands(if (case == Case.ReplacementLarge || case == Case.SourceChurnLarge) images[1] else images[0], 1))
        }

        private fun prepare(
            commands: List<DrawCommand>,
            previous: Any?,
        ): Any {
            val layers = partition.invoke(null, commands, case.extent, 1, true, false)
            val entry = prepare
            return if (entry == null) constructor.newInstance(layers, 1, 0L, 0L, true, null) else entry.invoke(companion, layers, 1, true, previous, 0L, 0L)
        }

        private fun commands(
            image: DrawImage,
            generation: Int,
        ): List<DrawCommand> {
            val size = case.extent
            val columns = (size.width + 255) / 256
            val rows = (size.height + 255) / 256
            return listOf(DrawCommand.FillRectangle(IntRect(0, 0, size.width, size.height), ArgbColor(0xFF102030.toInt()))) +
                (0 until columns * rows).flatMap { group ->
                    val left = group % columns * 256
                    val top = group / columns * 256
                    val right = minOf(size.width, left + 256)
                    val bottom = minOf(size.height, top + 256)
                    val area = IntRect(left, top, right, bottom)
                    val count = when (case) {
                        Case.DenseOddLarge -> 3
                        Case.NoOpHeavyLarge, Case.CutoffHeavyLarge -> 8
                        Case.MixedPassesLarge -> group % 17 + 1
                        else -> 4
                    }
                    val scroll = if (case == Case.ScrollLarge) generation * 0.25f else 0f
                    val sampled = List(count) { pass ->
                        val sparse = case == Case.SparseLarge && pass % 2 == 0
                        val tint = tint(case, group, pass, generation)
                        DrawCommand.SampledImage(
                            image,
                            FloatRect(0.125f, 0.375f, image.size.width - 0.125f, image.size.height - 0.25f),
                            FloatRect(left + scroll + if (sparse) (right - left) * 0.25f + 0.125f else 0.25f, top + if (sparse) (bottom - top) * 0.25f + 0.125f else 0.125f, right + scroll - if (sparse) (right - left) * 0.25f + 0.125f else 0.125f, bottom - if (sparse) (bottom - top) * 0.25f + 0.125f else 0.375f),
                            ArgbColor(tint),
                            alphaCutoff = if (case == Case.CutoffHeavyLarge && pass < 6) 1f else 0.1f,
                            orientation = if (pass % 2 == 0) SampledImageOrientation.Normal else SampledImageOrientation.FlipHorizontal,
                        )
                    }
                    val fills =
                        if (case == Case.ActivePassSwapLarge) {
                            listOf(DrawCommand.FillRectangle(IntRect(left, top, minOf(right, left + 127), bottom), ArgbColor(if (generation == 0) 0x00ABCDEF else 0x80445566.toInt())), DrawCommand.FillRectangle(IntRect(minOf(right, left + 128), top, right, bottom), ArgbColor(if (generation == 0) 0x80112233.toInt() else 0x00ABCDEF)))
                        } else {
                            listOf(DrawCommand.FillRectangle(area, ArgbColor(0x40213759 xor (group * 7919))))
                        }
                    fills + sampled + if (case == Case.NoOpHeavyLarge) List(8) { DrawCommand.FillRectangle(area, ArgbColor(0x00ABCDEF)) } else emptyList()
                }
        }

        private fun plan(
            inputs: Any,
            retain: Boolean,
        ) {
            val images = portable.invoke(inputs) as List<*>
            val previous = if (retain) previousImages else null
            val matches = previous?.let { matchImages.invoke(null, it, images) as IntArray }
            val base = images.mapIndexed { index, image ->
                val source = matches?.get(index) ?: -1
                if (0 <= source) previousReservations[source] else reservationSize.invoke(image) as IntSize
            }
            val planned = targetPlan?.invoke(targetCompanion, images, matches, base)
            val reserved =
                if (planned == null) base else {
                    val values = getter(planned.javaClass, "getReservations").invoke(planned) as List<*>
                    values.drop(1).map { it as IntSize }
                }
            check(reserved.size == images.size)
            lastPlan = planned
            lastReservations = reserved
            if (retain) {
                previousImages = images
                previousReservations = reserved
                lastPlan = planned
            } else if (previousImages == null) {
                previousImages = images
                previousReservations = reserved
                lastPlan = planned
            }
        }

        private fun verify(
            inputs: Any,
            commands: List<DrawCommand>,
        ) {
            val expected = rasterizeHeadless(commands, case.extent).copyArgb()
            val images = portable.invoke(inputs) as List<*>
            check(images.isNotEmpty())
            maps(inputs).forEach { map ->
                val index = indices.invoke(map) as DrawImage
                val factor = factors.invoke(map) as DrawImage
                check(index.size.height % 3 == 0 && factor.size.width == 1536)
                check((sources.invoke(map) as List<*>).size == index.size.height / 3)
            }
            val cold = prepare(commands, null)
            val coldImages = portable.invoke(cold) as List<*>
            check(images.size == coldImages.size)
            images.forEachIndexed { index, image ->
                val actual = rasterize.invoke(image) as HeadlessImage
                val reference = rasterize.invoke(coldImages[index]) as HeadlessImage
                check(actual.copyArgb().contentEquals(reference.copyArgb()))
                val offset = origin.invoke(image) as IntOffset
                val pixels = actual.copyArgb()
                for (y in 0 until actual.size.height) {
                    for (x in 0 until actual.size.width) check(pixels[y * actual.size.width + x] == expected[(offset.y + y) * case.extent.width + offset.x + x])
                }
                val map = composition.invoke(image)
                val fresh = composition.invoke(coldImages[index])
                check((map == null) == (fresh == null))
                if (map != null && fresh != null) {
                    check((indices.invoke(map) as DrawImage).copyArgb().contentEquals((indices.invoke(fresh) as DrawImage).copyArgb()))
                    check((factors.invoke(map) as DrawImage).copyArgb().contentEquals((factors.invoke(fresh) as DrawImage).copyArgb()))
                    val actualSources = sources.invoke(map) as List<*>
                    val freshSources = sources.invoke(fresh) as List<*>
                    check(actualSources.size == freshSources.size && actualSources.indices.all { actualSources[it] === freshSources[it] })
                }
            }
            if (case == Case.SmallSourceFallback) check(maps(inputs).isEmpty()) else check(maps(inputs).isNotEmpty())
        }

        private fun maps(inputs: Any): List<Any> = (portable.invoke(inputs) as List<*>).mapNotNull { composition.invoke(it) }
    }

    /**
     * Complete fixture/work checks use actual archives before JMH starts; diagnostics never replace raw CPU receipts.
     */
    public companion object {
        /**
         * Checks all 30 declared fresh/replacement rows and their real source/metadata ownership outside timing.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(CompositionTargetsBenchmark::class.java), setOf("avgt")).size == 30)
            val loaded =
                try {
                    Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs", false, javaClass.classLoader)
                    true
                } catch (_: ClassNotFoundException) {
                    false
                }
            Case.entries.forEach { case ->
                val scene = Scene()
                scene.case = case
                try {
                    if (loaded) {
                        scene.setup()
                    } else {
                        val commands = scene.prepareSources()
                        check(commands.size == 2 && commands.all { it.isNotEmpty() })
                        check((commands[0] === commands[1]) == case.static)
                        if (case.extent == IntSize(64, 64)) commands.forEach { check(rasterizeHeadless(it, case.extent).size == case.extent) }
                    }
                } finally {
                    scene.close()
                }
            }
        }

        /**
         * Writes original untimed actual image identity/area diagnostics with fixture and loaded archive identities.
         */
        @JvmStatic
        public fun main(args: Array<String>) {
            require(args.size == 1)
            val rows = JsonArray()
            Case.entries.forEach { case ->
                val scene = Scene()
                scene.case = case
                try {
                    scene.setup()
                    rows.add(scene.work())
                } finally {
                    scene.close()
                }
            }
            PerformanceJson.writeNew(
                Path.of(args.single()),
                JsonObject().apply {
                    addProperty("diagnostic", "composition-targets-work-v1")
                    addProperty("fixture_archive_sha256", ArtifactIdentity.fullCodeSource(CompositionTargetsBenchmark::class.java))
                    add(
                        "runtime",
                        LoadedArtifactMetadata
                            .capture(
                                CompositionTargetsBenchmark::class.java.classLoader,
                                mapOf("api" to "dev.s7a.strata.render.DrawImage", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage", "fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs"),
                                setOf("api", "core", "headless", "fabric"),
                            ).also(LoadedArtifactMetadata::verifyComplete),
                    )
                    add("cases", rows)
                },
            )
        }

        private fun getter(
            type: Class<*>,
            prefix: String,
        ): Method = type.declaredMethods.single { it.name.startsWith(prefix) && it.parameterCount == 0 }

        private fun identities(values: List<Any>): Set<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(values) }

        private fun pixels(values: Set<Any>): Int =
            values.sumOf {
                val image = it as DrawImage
                image.size.width * image.size.height
            }

        private fun source(
            extent: Int,
            phase: Int,
        ): DrawImage = createDrawImage(IntSize(extent, extent)) { x, y -> ((x * 19 + y * 17 and 255) shl 24) or (((x * 73471 + y * 1337) xor phase) and 0xFFFFFF) }

        private fun tint(
            case: Case,
            group: Int,
            pass: Int,
            generation: Int,
        ): Int {
            if (case == Case.NoOpHeavyLarge && pass < 6) return 0x00ABCDEF
            val stable =
                when (case) {
                    Case.ReplacementLarge, Case.SourceChurnLarge, Case.ScrollLarge, Case.ActivePassSwapLarge -> true
                    else -> false
                }
            val changed = if (stable || (case == Case.OneDirtyLarge && group != 0)) 0 else generation
            return 0x80BFD7EF.toInt() xor changed
        }

    }
}
