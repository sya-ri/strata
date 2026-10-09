@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Verifies bounded early exact-map reuse, fresh ledgers, source identity and current-only CPU ownership without Minecraft.
 */
internal class FabricMinecraftPreparedInputsTest {
    @Test
    fun rebuiltListsReuseWholeMapsAndShareNewFactorsAcrossTiles() {
        val image = source()
        val layers = (0 until 4).map { layer(image, it) }
        val first = FabricMinecraftFrameInputs.prepare(layers, 1, true)
        val maps = first.portable.map { checkNotNull(it.composition) }
        maps.forEach { assertSame(maps.first().factors, it.factors) }
        val nextLayers = (0 until 4).map { layer(image, it) }
        val next = FabricMinecraftFrameInputs.prepare(nextLayers, 1, true, first)
        next.portable.forEachIndexed { index, input ->
            assertSame(maps[index], input.composition)
            assertSame(nextLayers[index].commands, input.commands)
        }
        assertSame(nextLayers, next.layers)
        val separate = FabricMinecraftFrameInputs.prepare(nextLayers, 1, true)
        assertNotSame(maps.first(), separate.portable.first().composition)
        assertNotSame(maps.first().factors, checkNotNull(separate.portable.first().composition).factors)
        assertArrayEquals(first.portable.first().rasterize().copyArgb(), next.portable.first().rasterize().copyArgb())
    }

    @Test
    fun changedControlsAndSameExtentSourcesCopyAxesWithoutMutatingOldMaps() {
        val image = source()
        val background = createDrawImage(image.size) { x, y -> ((y * 23 + x * 13 and 255) shl 24) or ((y * 73471 + x * 7919) and 0xFFFFFF) }
        val replacement = createDrawImage(image.size, image.copyArgb())
        val different = createDrawImage(image.size) { x, y -> 0xFE33AACC.toInt() xor (x * 1337 + y * 7919) }
        val reference = FabricMinecraftCompositionMapTest()
        for (origin in listOf(IntOffset.Zero, IntOffset(16_777_219, 17_999_997))) {
            val bounds = IntRect(origin.x, origin.y, origin.x + 64, origin.y + 64)
            for (scale in 1..4) {
                for (orientation in SampledImageOrientation.entries) {
                    val sample = DrawCommand.SampledImage(image, FloatRect(0.125f, 0.375f, 127.875f, 127.75f), FloatRect(bounds.left + 0.25f, bounds.top + 0.125f, bounds.right - 0.125f, bounds.bottom - 0.375f), ArgbColor(0x80BFD7EF.toInt()), alphaCutoff = 0.1f, orientation = orientation)
                    val prefix = listOf(DrawCommand.BlitImagePixels(background, IntRect(0, 0, 128, 128), bounds), DrawCommand.PushClip(bounds), DrawCommand.PushFractionalClip(FloatRect(bounds.left + 0.125f, bounds.top + 0.375f, bounds.right - 0.125f, bounds.bottom - 0.25f)))
                    val suffix = listOf(DrawCommand.PopClip, DrawCommand.PopClip)
                    val original = FabricMinecraftFrameLayer.Portable(prefix + sample + suffix, bounds, absoluteCoordinates = true)
                    val first = FabricMinecraftFrameInputs.prepare(listOf(original), scale, true)
                    val old = checkNotNull(first.portable.single().composition)
                    assertTrue(0L < old.axisEntriesWritten)
                    val saved = old.indices.copyArgb()
                    val boundary = (128f / 255f) * (128f / 255f)
                    val samples = listOf(sample.copy(image = replacement), sample.copy(image = different), sample.copy(tint = ArgbColor(0xFE0102FF.toInt())), sample.copy(tint = ArgbColor(0)), sample.copy(alphaCutoff = Math.nextDown(boundary)), sample.copy(alphaCutoff = boundary), sample.copy(alphaCutoff = Math.nextUp(boundary)))
                    for (changed in samples) {
                        val commands = prefix + changed + suffix
                        val next = FabricMinecraftFrameInputs.prepare(listOf(FabricMinecraftFrameLayer.Portable(commands, bounds, absoluteCoordinates = true)), scale, true, first)
                        val map = checkNotNull(next.portable.single().composition)
                        val cold = checkNotNull(FabricMinecraftCompositionMap.create(commands, bounds.size, scale, origin, FabricMinecraftSamplingBudget()))
                        assertNotSame(old, map)
                        assertNotSame(old.indices, map.indices)
                        val active = changed.tint.value ushr 24 != 0 && changed.alphaCutoff <= (changed.tint.value ushr 24).toFloat() / 255f
                        assertEquals(if (active) 0L else cold.axisEntriesWritten, map.axisEntriesWritten)
                        assertTrue(map.equivalent(cold))
                        if (active) assertSame(changed.image, map.sources.last()) else assertFalse(map.sources.any { it === changed.image })
                        assertArrayEquals(rasterizeHeadlessRegion(commands, bounds, scale).copyArgb(), reference.compose(map))
                        assertArrayEquals(saved, old.indices.copyArgb())
                        map.indices.copyArgb().fill(0)
                        assertArrayEquals(saved, old.indices.copyArgb())
                    }
                }
            }
        }
    }

    @Test
    fun insertionRemovalAndOneDirtyTileKeepOnlyExactPreviousMaps() {
        val image = source()
        val layers = (0 until 3).map { layer(image, it) }
        val first = FabricMinecraftFrameInputs.prepare(layers, 1, true)
        val inserted = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 3)) + layers.map { layer(image, it.bounds.left / 64) }, 1, true, first)
        for (index in 0 until 3) assertSame(first.portable[index].composition, inserted.portable[index + 1].composition)
        val removed = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 0), layer(image, 2)), 1, true, first)
        assertSame(first.portable[0].composition, removed.portable[0].composition)
        assertSame(first.portable[2].composition, removed.portable[1].composition)
        val changed = listOf(layer(image, 0), layer(image, 1, tint = 0x80BFD7EE.toInt()), layer(image, 2))
        val dirty = FabricMinecraftFrameInputs.prepare(changed, 1, true, first)
        assertSame(first.portable[0].composition, dirty.portable[0].composition)
        assertNotSame(first.portable[1].composition, dirty.portable[1].composition)
        assertSame(first.portable[2].composition, dirty.portable[2].composition)
        val fresh = FabricMinecraftFrameInputs.prepare(changed, 1, true)
        dirty.portable.forEachIndexed { index, input -> assertTrue(checkNotNull(input.composition).equivalent(checkNotNull(fresh.portable[index].composition))) }
    }

    @Test
    fun originalGeometryScaleClipCutoffOrderAndSourceReplacementInvalidateEarlyReuse() {
        val image = source()
        val original = layer(image, 0)
        val first = FabricMinecraftFrameInputs.prepare(listOf(original), 1, true)
        val previousMap = checkNotNull(first.portable.single().composition)
        val sample = original.commands.last() as DrawCommand.SampledImage
        val variants =
            listOf(
                original.commands.dropLast(1) + sample.copy(image = createDrawImage(image.size, image.copyArgb())),
                original.commands.dropLast(1) + sample.copy(tint = ArgbColor(0x80BFD7EE.toInt())),
                original.commands.dropLast(1) + sample.copy(alphaCutoff = Math.nextUp(sample.alphaCutoff)),
                original.commands.dropLast(1) + sample.copy(source = FloatRect(0.125f, 0.25f, 127.875f, 127.75f)),
                original.commands.dropLast(1) + sample.copy(destination = FloatRect(0.25f, 0.125f, 63.75f, 63.875f)),
                original.commands.dropLast(1) + sample.copy(orientation = SampledImageOrientation.FlipHorizontal),
                original.commands.reversed(),
                listOf((original.commands.first() as DrawCommand.FillRectangle).copy(color = ArgbColor(0x40213758))) + original.commands.drop(1),
                listOf(DrawCommand.BlitImage(image, IntRect(0, 0, 128, 128), original.bounds)) + original.commands,
                listOf(DrawCommand.BlitImagePixels(image, IntRect(0, 0, 128, 128), original.bounds)) + original.commands,
                listOf(DrawCommand.PushFractionalClip(FloatRect(0.125f, 0.375f, 63.875f, 63.75f))) + original.commands + DrawCommand.PopClip,
            )
        for (commands in variants) {
            val layers = listOf(FabricMinecraftFrameLayer.Portable(commands, original.bounds, absoluteCoordinates = true))
            val next = FabricMinecraftFrameInputs.prepare(layers, 1, true, first)
            val fresh = FabricMinecraftFrameInputs.prepare(layers, 1, true)
            assertNotSame(previousMap, next.portable.single().composition)
            assertEquals(first.portable.single().samePreparedAxes(next.portable.single()), checkNotNull(next.portable.single().composition).axisEntriesWritten == 0L)
            assertTrue(checkNotNull(next.portable.single().composition).equivalent(checkNotNull(fresh.portable.single().composition)))
            assertArrayEquals(fresh.portable.single().rasterize().copyArgb(), next.portable.single().rasterize().copyArgb())
        }
        val scaled = FabricMinecraftFrameInputs.prepare(listOf(original), 2, true, first)
        assertNotSame(previousMap, scaled.portable.single().composition)
        val moved = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 1)), 1, true, first)
        assertNotSame(previousMap, moved.portable.single().composition)
        val disabled = FabricMinecraftFrameInputs.prepare(listOf(original), 1, false, first)
        assertNull(disabled.portable.single().composition)
        assertArrayEquals(previousMap.indices.copyArgb(), checkNotNull(first.portable.single().composition).indices.copyArgb())
    }

    @Test
    fun reusedLargeMapsKeepTheCurrentByteLimitAndOverflowChecks() {
        val image = source()
        val size = IntSize(2048, 1536)
        val bounds = IntRect(0, 0, size.width, size.height)
        val sample = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 128f, 128f), FloatRect(0f, 0f, 2048f, 1536f), ArgbColor(0x80BFD7EF.toInt()), alphaCutoff = 0.1f)
        val original = FabricMinecraftFrameLayer.Portable(listOf(sample), bounds, absoluteCoordinates = true)
        val first = FabricMinecraftFrameInputs.prepare(List(2) { original }, 1, true)
        assertEquals(2, first.portable.count { it.composition != null })
        val rebuilt = FabricMinecraftFrameLayer.Portable(listOf(sample.copy()), bounds, absoluteCoordinates = true)
        val next = FabricMinecraftFrameInputs.prepare(List(3) { rebuilt }, 1, true, first)
        val fresh = FabricMinecraftFrameInputs.prepare(List(3) { rebuilt }, 1, true)
        assertEquals(listOf(true, true, false), next.portable.map { it.composition != null })
        assertEquals(fresh.portable.map { it.composition != null }, next.portable.map { it.composition != null })
        for (index in 0 until 2) assertSame(first.portable[index].composition, next.portable[index].composition)
        assertThrows(ArithmeticException::class.java) { FabricMinecraftFrameInputs.prepare(listOf(rebuilt), Int.MAX_VALUE, true, first) }
        assertSame(first.portable.first().composition, next.portable.first().composition)
    }

    @Test
    fun everyReusedMapSpendsTheIndependentCurrentOutputAndPassLedger() {
        val image = source()
        val command = (layer(image, 0).commands.last() as DrawCommand.SampledImage)
        val bounds = IntRect(0, 0, 64, 64)
        val layer = FabricMinecraftFrameLayer.Portable(List(4) { command }, bounds, absoluteCoordinates = true)
        val first = FabricMinecraftFrameInputs.prepare(List(256) { layer }, 1, true)
        assertEquals(256, first.portable.count { it.composition != null })
        val next = FabricMinecraftFrameInputs.prepare(List(257) { layer }, 1, true, first)
        assertEquals(256, next.portable.count { it.composition != null })
        assertNull(next.portable.last().composition)
        for (index in 0 until 256) assertSame(first.portable[index].composition, next.portable[index].composition)
        val visible = IntRect(0, 0, 64, 64)
        val direct = command.copy(tint = ArgbColor(-1), alphaCutoff = 0f)
        val sampling = FabricMinecraftSamplingMap(direct, visible, 1)
        val directLayer = FabricMinecraftFrameLayer.Sampled(direct, null, visible, sampling)
        val withDirect = FabricMinecraftFrameInputs.prepare(listOf(directLayer) + List(256) { layer }, 1, true, first)
        assertEquals(255, withDirect.portable.count { it.composition != null })
        assertNull(withDirect.portable.last().composition)
        val expected = FabricMinecraftFrameInputs.prepare(listOf(directLayer) + List(256) { layer }, 1, true)
        assertEquals(expected.portable.map { it.composition != null }, withDirect.portable.map { it.composition != null })
    }

    @Test
    fun keysRequireFactorySourceIdentityAndAvailabilityIsRecheckedAfterReuse() {
        val image = source()
        val first = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 0)), 1, true)
        val next = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 0)), 1, true, first)
        assertSame(first.portable.single().composition, next.portable.single().composition)
        val replacement = createDrawImage(image.size, image.copyArgb())
        assertEquals(image, replacement)
        val changed = FabricMinecraftFrameInputs.prepare(listOf(layer(replacement, 0)), 1, true, next)
        assertNotSame(next.portable.single().composition, changed.portable.single().composition)
        assertSame(replacement, changed.sampled.single())
        val oldCpu = FabricMinecraftPortableImage(layer(image, 0).commands, IntSize(64, 64), 1)
        val newCpu = FabricMinecraftPortableImage(layer(replacement, 0).commands, IntSize(64, 64), 1)
        assertFalse(oldCpu.samePreparedInputs(newCpu))
        assertFalse(oldCpu.equivalent(newCpu))
        var unavailableCalls = 0
        val fallback =
            next.resolve({
                unavailableCalls += 1
                false
            }) { error("There are no direct layers") }
        assertTrue(0 < unavailableCalls)
        assertNull(fallback.portable.single().composition)
        assertSame(next, next.resolve({ true }) { error("Restored composed sources") })
    }

    @Test
    fun matchingIsBoundedAndFailedReplacementLeavesIndependentCurrentInputs() {
        val image = source()
        val commands = layer(image, 0).commands
        val previous = FabricMinecraftPortableImage(List(8192) { commands.first() }, IntSize(64, 64), 1)
        val large = FabricMinecraftPortableImage(List(8192) { commands.first() }, IntSize(64, 64), 1)
        assertArrayEquals(intArrayOf(-1), matchFabricMinecraftPreparedInputs(listOf(previous), listOf(large)))
        val atLimit = FabricMinecraftPortableImage(List(8191) { commands.first() }, IntSize(64, 64), 1)
        val atLimitNext = FabricMinecraftPortableImage(List(8191) { commands.first() }, IntSize(64, 64), 1)
        assertArrayEquals(intArrayOf(0), matchFabricMinecraftPreparedInputs(listOf(atLimit), listOf(atLimitNext)))
        val first = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 0)), 1, true)
        val map = checkNotNull(first.portable.single().composition)
        // Oversized internal CPU keys isolate matcher limits without allocating their inadmissible composition maps.
        val preceding = FabricMinecraftPortableImage(previous.commands, previous.size, 1, composition = map)
        assertArrayEquals(intArrayOf(-1), matchFabricMinecraftPreparedAxes(listOf(preceding), listOf(large), intArrayOf(-1)))
        val previousLimit = FabricMinecraftPortableImage(atLimit.commands, atLimit.size, 1, composition = map)
        assertArrayEquals(intArrayOf(0), matchFabricMinecraftPreparedAxes(listOf(previousLimit), listOf(atLimitNext), intArrayOf(-1)))
        assertArrayEquals(intArrayOf(-1), matchFabricMinecraftPreparedAxes(listOf(previousLimit), listOf(atLimitNext), intArrayOf(0)))
        // An unmatched pop is an invalid internal description, not a supported Canvas source failure.
        val bad = FabricMinecraftFrameLayer.Portable(commands + DrawCommand.PopClip, IntRect(0, 0, 64, 64), absoluteCoordinates = true)
        assertThrows(IllegalArgumentException::class.java) { FabricMinecraftFrameInputs.prepare(listOf(bad), 1, true, first) }
        var current = first
        repeat(100) {
            current = FabricMinecraftFrameInputs.prepare(listOf(layer(image, 0)), 1, true, current)
            assertSame(map, current.portable.single().composition)
        }
        assertTrue(current.javaClass.declaredFields.filter { Modifier.isStatic(it.modifiers).not() }.none { it.type == FabricMinecraftFrameInputs::class.java })
        assertTrue(map.javaClass.declaredFields.filter { Modifier.isStatic(it.modifiers).not() }.none { it.type == FabricMinecraftFrameInputs::class.java })
        assertFalse(first.portable.single().samePreparedInputs(FabricMinecraftPortableImage(commands, IntSize(64, 64), 1, IntOffset(1, 0))))
    }

    private fun source(): DrawImage = createDrawImage(IntSize(128, 128)) { x, y -> ((x * 19 + y * 17 and 255) shl 24) or ((x * 73471 + y * 1337) and 0xFFFFFF) }

    private fun layer(
        image: DrawImage,
        index: Int,
        tint: Int = 0x80BFD7EF.toInt(),
    ): FabricMinecraftFrameLayer.Portable {
        val bounds = IntRect(index * 64, 0, (index + 1) * 64, 64)
        val commands =
            listOf(
                DrawCommand.FillRectangle(bounds, ArgbColor(0x40213759)),
                DrawCommand.SampledImage(image, FloatRect(0f, 0f, 128f, 128f), FloatRect(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 64f), ArgbColor(tint), alphaCutoff = 0.1f),
            )
        return FabricMinecraftFrameLayer.Portable(commands, bounds, absoluteCoordinates = true)
    }
}
