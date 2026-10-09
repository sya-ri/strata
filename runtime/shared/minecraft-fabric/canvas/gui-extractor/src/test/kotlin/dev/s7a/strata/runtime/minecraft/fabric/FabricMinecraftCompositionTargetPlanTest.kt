@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks complete extent selection, rounded memory admission, exact matched reservations and bounded source-free plan retention.
 */
internal class FabricMinecraftCompositionTargetPlanTest {
    private val source = createDrawImage(IntSize(128, 128)) { x, y -> ((x * 19 + y * 17 and 255) shl 24) or (x * 1337 + y * 7919 and 0xFFFFFF) }

    @Test
    fun repeatedChangedExtentsShareOnlyWithinTheOriginalCompleteRoundedReservation() {
        for (count in listOf(2, 3, 32, 256)) {
            val budget = FabricMinecraftSamplingBudget()
            val images = List(count) { image(IntSize(64, 64), budget) }
            val plan = checkNotNull(FabricMinecraftCompositionTargetPlan.create(images, null))
            assertEquals(setOf(IntSize(64, 64)), plan.shapes)
            assertEquals(count + 1, plan.reservations.size)
            assertTrue(bytes(plan.reservations) <= bytes(images.map { it.reservationSize }))
            images.indices.forEach { assertEquals(checkNotNull(images[it].composition).singleOutputReservationSize, plan.reservations[it + 1]) }
            val unchanged = IntArray(count) { it }
            assertNull(FabricMinecraftCompositionTargetPlan.create(images, unchanged))
        }
    }

    @Test
    fun singleUniqueAndCpuFallbackShapesKeepTheirOrdinaryReservations() {
        val budget = FabricMinecraftSamplingBudget()
        val images = listOf(image(IntSize(64, 64), budget), image(IntSize(65, 64), budget), image(IntSize(64, 65), budget))
        assertNull(FabricMinecraftCompositionTargetPlan.create(images, null))
        val cpu = FabricMinecraftPortableImage(emptyList(), IntSize(64, 64), 1)
        assertNull(FabricMinecraftCompositionTargetPlan.create(listOf(images[0], cpu), null))
        assertNull(FabricMinecraftCompositionTargetPlan.create(listOf(images[0]), null))
        assertNull(FabricMinecraftCompositionTargetPlan.create(List(257) { images[0] }, null))
    }

    @Test
    fun replacementPlansPreserveMatchedReducedExtentsAndSelectOnlyNewWork() {
        val budget = FabricMinecraftSamplingBudget()
        val images = List(4) { image(IntSize(64, 64), budget) }
        val old = checkNotNull(FabricMinecraftCompositionTargetPlan.create(images, null))
        val retained = old.reservations.drop(1)
        val changed = intArrayOf(0, -1, -1, 3)
        val base = images.mapIndexed { index, image -> if (0 <= changed[index]) retained[index] else image.reservationSize }
        val next = checkNotNull(FabricMinecraftCompositionTargetPlan.create(images, changed, base))
        assertEquals(retained[0], next.reservations[1])
        assertEquals(retained[3], next.reservations[4])
        assertTrue(bytes(next.reservations) <= bytes(base))
        assertNull(FabricMinecraftCompositionTargetPlan.create(images, intArrayOf(0, 1, -1, 3), base))
    }

    @Test
    fun multiplePhysicalShapesAndRejectedReservationsRetainNoDrawingOrSourceHistory() {
        val budget = FabricMinecraftSamplingBudget()
        val images = listOf(image(IntSize(64, 64), budget), image(IntSize(64, 64), budget), image(IntSize(128, 64), budget), image(IntSize(128, 64), budget)).toMutableList()
        val plan = checkNotNull(FabricMinecraftCompositionTargetPlan.create(images, null))
        assertEquals(setOf(IntSize(64, 64), IntSize(128, 64)), plan.shapes)
        assertTrue(bytes(plan.reservations) <= bytes(images.map { it.reservationSize }))
        val saved = plan.reservations.toList()
        images.clear()
        assertEquals(saved, plan.reservations)
        assertEquals(2, plan.shapes.size)
        assertFalse(plan.javaClass.declaredFields.any { DrawImage::class.java.isAssignableFrom(it.type) || it.type == FabricMinecraftPortableImage::class.java })
        val pair = List(2) { image(IntSize(64, 64), FabricMinecraftSamplingBudget()) }
        assertNull(FabricMinecraftCompositionTargetPlan.create(pair, null, List(2) { IntSize(1, 1) }))
    }

    private fun image(
        size: IntSize,
        budget: FabricMinecraftSamplingBudget,
    ): FabricMinecraftPortableImage {
        val commands = listOf(DrawCommand.FillRectangle(IntRect(0, 0, size.width, size.height), ArgbColor(0x40213759)), DrawCommand.SampledImage(source, FloatRect(0f, 0f, 128f, 128f), FloatRect(0f, 0f, size.width.toFloat(), size.height.toFloat()), ArgbColor(0x80BFD7EF.toInt()), alphaCutoff = 0.1f))
        val map = checkNotNull(FabricMinecraftCompositionMap.create(commands, size, 1, IntOffset.Zero, budget))
        return FabricMinecraftPortableImage(commands, size, 1, composition = map)
    }

    private fun bytes(extents: List<IntSize>): Long = extents.sumOf { it.width.toLong() * it.height * 4L }
}
