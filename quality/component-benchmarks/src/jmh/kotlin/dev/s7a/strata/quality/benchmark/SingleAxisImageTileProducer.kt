package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftTileBackgroundReference
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.IdentityHashMap

/**
 * Calls the real image-background node and the real guarded collector from the supplied core/Minecraft archives.
 * Both original and candidate collector classes are supported without replacing production painting or compaction.
 * Reflection setup, immutable source creation and diagnostic materialization stay outside timed callback operations.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SingleAxisImageTileProducer(
    private val workload: SingleAxisImageTileWorkload,
    private val image: DrawImage,
) {
    private val ownerType = Class.forName("dev.s7a.strata.runtime.OwnerGuard")
    private val owner = ownerType.getDeclaredConstructor().also { check(it.trySetAccessible()) }.newInstance()
    private val scopeType =
        try {
            Class.forName("dev.s7a.strata.runtime.PaintPipeline\$LocalPaintScope")
        } catch (_: ClassNotFoundException) {
            Class.forName("dev.s7a.strata.runtime.LocalPaintScope")
        }
    private val scopeConstructor = scopeType.getDeclaredConstructor(ownerType, IntSize::class.java).also { check(it.trySetAccessible()) }
    private val snapshot = method(scopeType, "snapshot", 0)
    private val close = method(scopeType, "close", 0)
    private val admission = method(Class.forName("dev.s7a.strata.runtime.BlitCompositionKt"), "composeDenseBlits", 1)
    private val blitType = Class.forName("dev.s7a.strata.runtime.LocalDrawCommand\$BlitImage")
    private val composedType = Class.forName("dev.s7a.strata.runtime.LocalDrawCommand\$ComposedBlits")
    private val virtualType =
        try {
            Class.forName("dev.s7a.strata.runtime.SingleTexelImageTiles")
        } catch (_: ClassNotFoundException) {
            null
        }
    private val original = method(composedType, "getOriginal", 0)
    private val presented = method(composedType, "getCommands", 0)
    private val imageGetter = method(blitType, "getImage", 0)
    private val sourceGetter = method(blitType, "getSource", 0)
    private val destinationGetter = method(blitType, "getDestination", 0)
    private val node: PaintNode = createNode()

    /**
     * Includes collector construction, the genuine callback, immutable snapshot and scope closure.
     * Returns pre-admission commands; a candidate descriptor is not expanded inside this boundary.
     */
    internal fun collect(): List<*> {
        val scope = scopeConstructor.newInstance(owner, workload.design) as PaintScope
        return try {
            node.paint(scope)
            if (workload.mixed) scope.fillRectangle(IntRect(0, 0, 7, 5), ArgbColor(0x80442288.toInt()))
            snapshot.invoke(scope) as List<*>
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        } finally {
            close.invoke(scope)
        }
    }

    /**
     * Adds the original real dense-list admission, excluding lazy integer templates and global transformation.
     */
    internal fun collectAndAdmit(): List<*> = compact(collect())

    /**
     * Materializes diagnostic integer spans only after collecting, separate from eager callback allocations.
     * Reports actual collection membership, original representation and new-image payload, never timings or native work.
     */
    internal fun work(): JsonObject {
        val collected = collect()
        val commands = compact(collected)
        val spans = ArrayList<Any>()
        var virtualCells = 0
        var retainedCells = 0
        val originals = ArrayList<Any>()
        commands.forEach { command ->
            if (composedType.isInstance(command)) {
                val cells = original.invoke(command) as List<*>
                if (virtualType?.isInstance(cells) == true) {
                    virtualCells += cells.size
                } else {
                    retainedCells += cells.size
                }
                originals.addAll(cells.filterNotNull())
                spans.addAll((presented.invoke(command) as List<*>).filterNotNull())
            } else if (blitType.isInstance(command)) {
                retainedCells += 1
                originals.add(checkNotNull(command))
                spans.add(command)
            }
        }
        verifyOriginals(originals)
        val templates = IdentityHashMap<DrawImage, Boolean>()
        spans.forEach { command ->
            val source = imageGetter.invoke(command) as DrawImage
            if (source !== image) templates[source] = true
        }
        return JsonObject().apply {
            addProperty("case", workload.name)
            addProperty("actualCollectorClass", scopeType.name)
            addProperty("collectorEntries", collected.size)
            addProperty("eagerLocalBlits", collected.count(blitType::isInstance))
            addProperty("originalVirtualCells", virtualCells)
            addProperty("retainedMaterializedOriginalBlits", retainedCells)
            addProperty("integerPresentationSpans", spans.size)
            addProperty("derivedTemplateImages", templates.size)
            addProperty("derivedTemplatePayloadBytes", templates.keys.sumOf { it.size.width.toLong() * it.size.height * 4L })
            addProperty("sourceWidth", image.size.width)
            addProperty("sourceHeight", image.size.height)
            addProperty("localWidth", workload.design.width)
            addProperty("localHeight", workload.design.height)
        }
    }

    private fun compact(commands: List<*>): List<*> = admission.invoke(null, commands) as List<*>

    private fun createNode(): PaintNode {
        val element =
            if (workload.mapping == SingleAxisImageTileWorkload.Mapping.NineSlice) {
                val type = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftNineSliceImageBackgroundModifierKt")
                method(type, "createMinecraftNineSliceImageBackgroundModifier", 3).invoke(null, image, Insets.all(1), NineSliceCenterMode.Tiled)
            } else {
                val type = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftImageBackgroundModifierKt")
                val mapping = if (workload.mapping == SingleAxisImageTileWorkload.Mapping.Stretch) ImageScale.Stretch else ImageScale.Tile
                method(type, "createMinecraftImageBackgroundModifier", 2).invoke(null, image, mapping)
            } as ModifierElement
        element.type.validateErased(element)
        return element.type.createErased(element) as PaintNode
    }

    private fun verifyOriginals(commands: List<Any>) {
        if (workload.mapping == SingleAxisImageTileWorkload.Mapping.NineSlice) {
            check(commands.size == 9)
            check(commands.all { imageGetter.invoke(it) === image })
            return
        }
        val expected =
            if (workload.mapping == SingleAxisImageTileWorkload.Mapping.Stretch) {
                if (workload.design == IntSize.Zero) emptyList() else listOf(IntRect(0, 0, image.size.width, image.size.height) to IntRect(0, 0, workload.design.width, workload.design.height))
            } else {
                MinecraftTileBackgroundReference.scalar(image, workload.design).map { it.source to it.destination }
            }
        check(commands.size == expected.size)
        commands.zip(expected).forEach { (command, geometry) ->
            check(imageGetter.invoke(command) === image)
            check(sourceGetter.invoke(command) == geometry.first && destinationGetter.invoke(command) == geometry.second)
        }
    }

    private fun method(
        type: Class<*>,
        name: String,
        parameters: Int,
    ): Method = type.declaredMethods.single { it.name.substringBefore('$').contentEquals(name) && it.parameterCount == parameters }.also { check(it.trySetAccessible()) }
}
