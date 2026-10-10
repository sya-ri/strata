package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Reads actual presenter, map and sampled-owner state at loaded test boundaries without modifying runtime fields.
 * The boundary decodes genuine archive members rather than substituting a preparation/reuse implementation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSamplingCoveragePresentation {
    /** Detached observations of one actually prepared presenter generation; identities remain local to this test. */
    @Suppress("LongParameterList") // One immutable observation preserves the actual generation and its independent identities.
    internal data class Snapshot(
        internal val inputs: Any,
        internal val commandIdentity: Any,
        internal val commands: List<DrawCommand>,
        internal val maps: List<Any>,
        internal val viewport: IntSize,
        internal val capacity: Long,
        internal val preparationCount: Long,
    )

    /** Finds the unobfuscated owner or the legacy presentation collaborator. */
    @Suppress("StringLiteralComparison") // This exact private JVM field name is a reflection boundary, not a component discriminator.
    internal fun owner(screen: FabricMinecraftScreen): Any {
        val presentation = screen.javaClass.declaredFields.singleOrNull { it.name == "presentation" } ?: return screen
        check(presentation.trySetAccessible())
        return checkNotNull(presentation.get(screen))
    }

    /** Returns current actual inputs, or null until preparation has completed. */
    internal fun snapshot(screen: FabricMinecraftScreen): Snapshot? {
        val owner = owner(screen)
        val inputs = field(owner, "preparedInputs") ?: return null
        val commandIdentity = checkNotNull(field(owner, "preparedCommands"))
        val commands = (commandIdentity as List<*>).map { it as DrawCommand }
        val layers = getter(inputs, "Layers") as List<*>
        val sampled = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayer\$Sampled")
        val maps = layers.filter(sampled::isInstance).mapNotNull { getter(checkNotNull(it), "Sampling") }
        return Snapshot(inputs, commandIdentity, commands, maps, field(owner, "preparedViewport") as IntSize, getter(inputs, "CapacitySampledImages") as Long, field(owner, "framePreparationCount") as Long)
    }

    /** Actual immutable encoded map bytes for old-map retention and unchanged-output comparisons. */
    internal fun metadata(map: Any): IntArray = (getter(map, "Indices") as DrawImage).copyArgb()

    /** Reads the actual source object retained by one map. */
    internal fun source(map: Any): DrawImage = (getter(map, "Command") as DrawCommand.SampledImage).image

    /** Checks normal screen cleanup dropped all prepared list, viewport and CPU input references. */
    internal fun released(screen: FabricMinecraftScreen): Boolean {
        val owner = owner(screen)
        return field(owner, "preparedCommands") == null && field(owner, "preparedViewport") == null && field(owner, "preparedInputs") == null
    }

    /** Finds this screen's actual independently owned sampled manager. */
    internal fun manager(screen: FabricMinecraftScreen): Any = checkNotNull(field(checkNotNull(field(owner(screen), "sampledImages")), "device"))

    /** Native sampled resource count includes retired entries awaiting their actual initialization/GUI fences. */
    internal fun retained(manager: Any): Int = invoke(manager, "retainedResourceCount") as Int

    /** Checks actual queued entries still own unclosed GPU storage after screen-reference release. */
    internal fun pendingNative(manager: Any): Int = (field(manager, "entries") as Set<*>).count { entry ->
        val current = checkNotNull(entry)
        val resource = field(current, "resource")
        (field(current, "pendingGui") as Boolean) && resource != null && (field(resource, "closed") as Boolean).not()
    }

    /** Requires no remaining source identities or screen owners after terminal/native retirement. */
    internal fun noSourcesOrOwners(manager: Any): Boolean = (field(manager, "owners") as Set<*>).isEmpty() && (field(manager, "entries") as Set<*>).isEmpty()

    /** Reflects only named private diagnostic fields, on the same render thread that owns them. */
    internal fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let { field ->
        check(field.trySetAccessible())
        field.get(owner)
    }

    private fun getter(owner: Any, name: String): Any? = invoke(owner, "get$name")

    private fun invoke(owner: Any, name: String): Any? = owner.javaClass.declaredMethods.single { it.name.substringBefore('$') == name }.invoke(owner)
}
