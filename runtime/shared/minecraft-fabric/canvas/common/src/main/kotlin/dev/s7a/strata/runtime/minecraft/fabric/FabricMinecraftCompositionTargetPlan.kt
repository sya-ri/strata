package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Selects same-extent changed tiles which can share one preparation-only RGBA8 intermediate.
 * Each tile still owns its immutable final output; the independent current-frame admission ledger is unchanged.
 * Only extents and reservation values survive planning, with at most 128 shared shapes for 256 outputs.
 * A plan is discarded when rounded native reservations would exceed the original complete reservation.
 */
internal class FabricMinecraftCompositionTargetPlan private constructor(
    @get:JvmSynthetic internal val shapes: Set<IntSize>,
    @get:JvmSynthetic internal val reservations: List<IntSize>,
) {
    /**
     * Builds a checked reservation list with the workspace first, followed by the original image order.
     * Matched immutable outputs require no composition and therefore never enter a scratch group.
     */
    internal companion object {
        /**
         * Selects repeated changed extents only when the complete rounded reservation does not grow.
         * Existing matched reservations preserve the exact extent required by native immutable-resource reuse.
         */
        @JvmSynthetic
        internal fun create(
            images: List<FabricMinecraftPortableImage>,
            matches: IntArray?,
            existingReservations: List<IntSize> = images.map { it.reservationSize },
        ): FabricMinecraftCompositionTargetPlan? {
            if (256 < images.size) return null
            val counts = HashMap<IntSize, Int>()
            images.forEachIndexed { index, image ->
                if ((matches?.get(index) ?: -1) < 0) {
                    image.composition?.let { counts[it.physicalSize] = (counts[it.physicalSize] ?: 0) + 1 }
                }
            }
            val shapes = counts.filterValues { 2 <= it }.keys.toSet()
            if (shapes.isEmpty()) return null
            val width = shapes.maxOf { it.width }
            val bytes = shapes.fold(4096L) { total, size -> Math.addExact(total, Math.addExact(payload(size), 4096L)) }
            val workspace = extent(width, bytes)
            val reservations = ArrayList<IntSize>(images.size + 1)
            reservations.add(workspace)
            images.forEachIndexed { index, image ->
                val composition = image.composition
                val shared = (matches?.get(index) ?: -1) < 0 && composition != null && composition.physicalSize in shapes
                reservations.add(if (shared) checkNotNull(composition).singleOutputReservationSize else existingReservations[index])
            }
            val original = existingReservations.fold(0L) { total, size -> Math.addExact(total, payload(size)) }
            val planned = reservations.fold(0L) { total, size -> Math.addExact(total, payload(size)) }
            return if (planned <= original) FabricMinecraftCompositionTargetPlan(shapes, reservations.toList()) else null
        }

        private fun payload(size: IntSize): Long = Math.multiplyExact(Math.multiplyExact(size.width.toLong(), size.height.toLong()), 4L)

        private fun extent(
            width: Int,
            bytes: Long,
        ): IntSize {
            val row = width * 4L
            return IntSize(width, Math.toIntExact((Math.addExact(bytes, row - 1L)) / row))
        }
    }
}
