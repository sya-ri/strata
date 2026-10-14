@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Calls genuine native-free preparation members from the actual externally supplied Fabric archive.
 * Reflection is resolved before timing and uses the same boundary on both archive variants.
 * It never substitutes copied production sampling code or initializes a game/device/native source owner.
 */
public class SamplingCoverageRuntime {
    private val integer = checkNotNull(Int::class.javaPrimitiveType)
    private val boolean = checkNotNull(Boolean::class.javaPrimitiveType)
    private val long = checkNotNull(Long::class.javaPrimitiveType)
    private val mapClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingMap")
    private val sampledClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayer\$Sampled")
    private val portableClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayer\$Portable")
    private val inputClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs")
    private val mapConstructor = mapClass.getDeclaredConstructor(DrawCommand.SampledImage::class.java, IntRect::class.java, integer)
    private val inputConstructor = inputClass.getDeclaredConstructor(List::class.java, integer, long, long, boolean, List::class.java)
    private val partition = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayerKt").getDeclaredMethod("partitionFabricMinecraftFrame", List::class.java, IntSize::class.java, integer, boolean, boolean)
    private val inputCompanion = checkNotNull(inputClass.getDeclaredField("Companion").get(null))
    private val preparation =
        inputCompanion.javaClass.declaredMethods.single {
            it.name.substringBefore('$').contentEquals("prepare") &&
                it.parameterTypes.contentEquals(arrayOf<Class<*>>(List::class.java, IntSize::class.java, integer, boolean, boolean, boolean))
        }
    private val repartition = getter(inputClass, "Repartition")
    private val indices = getter(mapClass, "Indices")
    private val physicalSize = getter(mapClass, "PhysicalSize")
    private val sampling = getter(sampledClass, "Sampling")

    /**
     * Captured downstream description accessor, resolved before any timed operation.
     */
    internal val portableGetter: Method = getter(inputClass, "Portable")

    /**
     * Captured immutable source accessor for untimed lifetime controls.
     */
    internal val sampledGetter: Method = getter(inputClass, "Sampled")
    private val layers = getter(inputClass, "Layers")

    /**
     * Captured actual byte/image fallback accessor.
     */
    internal val capacityGetter: Method = getter(inputClass, "CapacitySampledImages")

    /**
     * Captured unsupported sampling accessor.
     */
    internal val ineligibleGetter: Method = getter(inputClass, "IneligibleSampledImages")

    /**
     * Captured unsupported tint accessor.
     */
    internal val tintFallbackGetter: Method = getter(inputClass, "TintFallbackImages")
    private val equivalent = mapClass.declaredMethods.single { it.name.substringBefore('$').contentEquals("equivalent") }
    private val sampledCommand = getter(sampledClass, "Command")
    private val sampledClip = getter(sampledClass, "Clip")
    private val sampledBounds = getter(sampledClass, "VisibleBounds")
    private val portableCommands = getter(portableClass, "Commands")
    private val portableBounds = getter(portableClass, "Bounds")

    /**
     * Typed detached view of actual prepared layer order, clips and immutable commands for the independent oracle.
     */
    public data class Trace(
        public val bounds: IntRect,
        public val commands: List<DrawCommand>,
    )

    /**
     * Constructs a fresh genuine exact map, preserving the runtime's original failure as the cause.
     */
    public fun map(
        command: DrawCommand.SampledImage,
        bounds: IntRect,
        density: Int,
    ): Any = originalFailure { mapConstructor.newInstance(command, bounds, density) }

    /**
     * Prepares the current complete CPU descriptions, including the bounded source-admission callback, composition/factor tables and sampled-source requests.
     */
    public fun prepare(
        input: SamplingCoverageFixture.Input,
        commands: List<DrawCommand> = input.commands,
    ): Any =
        originalFailure {
            checkNotNull(preparation.invoke(inputCompanion, commands, input.viewport, input.density, true, true, true))
        }

    /**
     * Qualifies the original five-argument partition and six-argument constructor outside timing.
     * The current wrapper must add its bounded CPU callback while retaining the same complete layers, metadata and source identities.
     * Independent scalar pixel/admission controls remain mandatory; this check only binds the genuine preparation entry points.
     */
    public fun verifyPreparationEntryPoints(input: SamplingCoverageFixture.Input) {
        val original =
            originalFailure {
                val layers = checkNotNull(partition.invoke(null, input.commands, input.viewport, input.density, true, true))
                inputConstructor.newInstance(layers, input.density, 0L, 0L, true, null)
            }
        val current = prepare(input)
        check(trace(current) == trace(original))
        check(capacity(current) == capacity(original) && ineligible(current) == ineligible(original))
        val currentMaps = maps(current)
        val originalMaps = maps(original)
        check(currentMaps.size == originalMaps.size)
        currentMaps.zip(originalMaps).forEach { (actual, expected) ->
            check(size(actual) == size(expected) && indices(actual).copyArgb().contentEquals(indices(expected).copyArgb()))
        }
        val currentSources = sources(current)
        val originalSources = sources(original)
        check(currentSources.size == originalSources.size && currentSources.zip(originalSources).all { (actual, expected) -> actual === expected })
        check(repartition.invoke(current) != null && repartition.invoke(original) == null)
    }

    /**
     * Returns the complete encoded immutable lookup; no source pixel copy is made.
     */
    public fun indices(map: Any): DrawImage = indices.invoke(map) as DrawImage

    /**
     * Physical map extent from actual runtime checked arithmetic.
     */
    public fun size(map: Any): IntSize = physicalSize.invoke(map) as IntSize

    /**
     * Counts the actual map objects in the produced ordered layers, never forecasting admission from dimensions.
     */
    public fun maps(inputs: Any): List<Any> = layers(inputs).filter(sampledClass::isInstance).mapNotNull { sampling.invoke(it) }

    /**
     * Complete actual ordered layer trace for independently defined admission and barrier assertions.
     */
    public fun layers(inputs: Any): List<Any> = (layers.invoke(inputs) as List<*>).map(::checkNotNull)

    /**
     * Decodes actual archive layer kinds at the reflection boundary, preserving every command occurrence and clip.
     */
    public fun trace(inputs: Any): List<Trace> =
        layers(inputs).map { layer ->
            when {
                sampledClass.isInstance(layer) -> {
                    val command = sampledCommand.invoke(layer) as DrawCommand.SampledImage
                    val clip = sampledClip.invoke(layer) as IntRect?
                    val commands = if (clip == null) listOf(command) else listOf(DrawCommand.PushClip(clip), command, DrawCommand.PopClip)
                    Trace(sampledBounds.invoke(layer) as IntRect, commands)
                }

                portableClass.isInstance(layer) -> {
                    val commands = (portableCommands.invoke(layer) as List<*>).map { it as DrawCommand }
                    Trace(portableBounds.invoke(layer) as IntRect, commands)
                }

                else -> {
                    error("The frozen CPU corpus has no opaque platform layers.")
                }
            }
        }

    /**
     * Existing placement-independent equivalence contract, independent of the scalar oracle checks.
     */
    public fun equivalent(
        first: Any,
        second: Any,
    ): Boolean = equivalent.invoke(first, second) as Boolean

    /**
     * Independently exercises the full 1024-pass limit without consuming byte or image capacity first.
     */
    public fun passBudget(): Boolean {
        val owner = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingBudget")
        val budget = owner.getDeclaredConstructor().newInstance()
        val admit = owner.declaredMethods.single { it.name.substringBefore('$').contentEquals("admitComposition") }
        val full = admit.invoke(budget, IntSize(1, 1), 1L, 1024) as Boolean
        val exhausted = admit.invoke(budget, IntSize(1, 1), 1L, 1) as Boolean
        return full && exhausted.not()
    }

    private fun getter(
        owner: Class<*>,
        property: String,
    ): Method = owner.declaredMethods.single { it.name.substringBefore('$').contentEquals("get$property") }

    private inline fun originalFailure(block: () -> Any): Any =
        try {
            block()
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
}
