package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import kotlin.jvm.JvmSynthetic

/**
 * Retains dense image-only paint for lazy owner-thread pattern compaction.
 * The detached collector snapshot remains exclusively owned by the retained paint list.
 * Original commands remain authoritative for fractional transforms and ineligible grids.
 */
internal fun composeDenseBlits(commands: List<LocalDrawCommand>): List<LocalDrawCommand> {
    if ((commands.size in 64..1_048_576).not()) return commands
    val image = (commands.first() as? LocalDrawCommand.BlitImage)?.image ?: return commands
    for (command in commands) {
        val blit = command as? LocalDrawCommand.BlitImage ?: return commands
        if (blit.image !== image) return commands
    }
    @Suppress("UNCHECKED_CAST") // The detached collector list is exclusively owned and every entry was checked above.
    val blits = commands as List<LocalDrawCommand.BlitImage>
    val bounds = blitBounds(blits) ?: return commands
    return listOf(LocalDrawCommand.ComposedBlits(blits, bounds))
}

/**
 * Validates disjoint rectangular source groups before replacing repeated tiles.
 * At most 32 immutable templates of at most 64 by 64 pixels belong to this retained paint list.
 */
internal fun compactBlitPatterns(
    commands: List<LocalDrawCommand.BlitImage>,
    bounds: IntRect,
): List<LocalDrawCommand.BlitImage> {
    if (commands is SingleTexelImageTiles) return commands.collapsed()
    val groups = linkedMapOf<IntRect, MutableList<LocalDrawCommand.BlitImage>>()
    for (command in commands) {
        groups.getOrPut(command.source) { mutableListOf() }.add(command)
        if (32 < groups.size) return commands
    }
    val rectangles = groups.values.map { blitBounds(it) ?: return commands }
    for (index in rectangles.indices) {
        if ((0 until index).any { overlaps(rectangles[index], rectangles[it]) }) return commands
        if (isBlitGrid(groups.values.elementAt(index), rectangles[index]).not()) return commands
    }
    if (rectangles.sumOf { it.width.toLong() * it.height } != bounds.width.toLong() * bounds.height) return commands
    return groups.values.flatMapIndexed { index, group -> compactBlitGroup(group, rectangles[index]) }
}

/**
 * Finds a nonempty bounding rectangle without overflowing its integer extents.
 */
private fun blitBounds(commands: List<LocalDrawCommand.BlitImage>): IntRect? {
    val first = commands.first().destination
    var left = first.left
    var top = first.top
    var right = first.right
    var bottom = first.bottom
    for ((_, _, destination) in commands) {
        left = minOf(left, destination.left)
        top = minOf(top, destination.top)
        right = maxOf(right, destination.right)
        bottom = maxOf(bottom, destination.bottom)
    }
    if ((right.toLong() - left in 1..Int.MAX_VALUE.toLong()).not() || (bottom.toLong() - top in 1..Int.MAX_VALUE.toLong()).not()) return null
    return IntRect(left, top, right, bottom)
}

/**
 * Tests half-open rectangle intersection before changing command order.
 */
private fun overlaps(
    first: IntRect,
    second: IntRect,
): Boolean = first.left < second.right && second.left < first.right && first.top < second.bottom && second.top < first.bottom

/**
 * Proves uniform row-major cells and complete coverage without per-tile division or storage.
 */
private fun isBlitGrid(
    commands: List<LocalDrawCommand.BlitImage>,
    bounds: IntRect,
): Boolean {
    val size = commands.first().destination
    if (bounds.width % size.width != 0 || bounds.height % size.height != 0) return false
    val columns = bounds.width / size.width
    if (columns.toLong() * (bounds.height / size.height) != commands.size.toLong()) return false
    var left = bounds.left
    var top = bounds.top
    for ((_, _, cell) in commands) {
        if (cell.width != size.width || cell.height != size.height) return false
        if (cell.left != left || cell.top != top) return false
        left = cell.right
        if (left == bounds.right) {
            left = bounds.left
            top = cell.bottom
        }
    }
    return left == bounds.left && top == bounds.bottom
}

/**
 * Uses tile-aligned templates only when their two pixel arrays fit the removed command allocation.
 * Stretched, sparse, and large source groups keep the original validated commands.
 */
private fun compactBlitGroup(
    commands: List<LocalDrawCommand.BlitImage>,
    bounds: IntRect,
): List<LocalDrawCommand.BlitImage> {
    val first = commands.first()
    val source = first.source
    if (commands.size < 4 || 64 < source.width || 64 < source.height) return commands
    if (source.width != first.destination.width || source.height != first.destination.height) return commands
    val size = boundedTemplateSize(source, bounds, 6L * commands.size) ?: return commands
    val width = size.width
    val height = size.height
    val image = repeatedBlitImage(first, IntSize(width, height))
    return buildList {
        var top = bounds.top
        while (top < bounds.bottom) {
            val bottom = minOf(bounds.bottom.toLong(), top.toLong() + height).toInt()
            var left = bounds.left
            while (left < bounds.right) {
                val right = minOf(bounds.right.toLong(), left.toLong() + width).toInt()
                add(LocalDrawCommand.BlitImage(image, IntRect(0, 0, right - left, bottom - top), IntRect(left, top, right, bottom)))
                left = right
            }
            top = bottom
        }
    }
}

/**
 * Chooses tile-aligned extents within the allocation budget without retaining a one-tile duplicate.
 */
private fun boundedTemplateSize(
    source: IntRect,
    bounds: IntRect,
    budget: Long,
): IntSize? {
    var width = minOf(bounds.width, source.width * (64 / source.width))
    var height = minOf(bounds.height, source.height * (64 / source.height))
    while (budget < width.toLong() * height) {
        if (source.width < width && (height <= width || height == source.height)) {
            width -= source.width
        } else if (source.height < height) {
            height -= source.height
        } else {
            return null
        }
    }
    return if (width == source.width && height == source.height) null else IntSize(width, height)
}

/**
 * Copies unblended source pixels into one bounded tile-aligned repeating template.
 * No global cache or source-sized snapshot is retained.
 */
private fun repeatedBlitImage(
    command: LocalDrawCommand.BlitImage,
    size: IntSize,
): DrawImage {
    val source = command.source
    val pixels = IntArray(size.width * size.height)
    for (y in 0 until size.height) {
        val offset = y * size.width
        if (source.height <= y) {
            val start = (y % source.height) * size.width
            pixels.copyInto(pixels, offset, start, start + size.width)
        } else {
            for (x in 0 until source.width) pixels[offset + x] = command.image.argbAt(source.left + x, source.top + y)
            var copied = source.width
            while (copied < size.width) {
                val count = minOf(copied, size.width - copied)
                pixels.copyInto(pixels, offset + copied, offset, offset + count)
                copied += count
            }
        }
    }
    return createDrawImage(size, pixels)
}

/**
 * Builds an immutable singleton-axis tile description, declining the original producer's arithmetic rejection paths.
 * The descriptor belongs only to the current local paint list and recreates scalar cells for fractional presentation.
 */
@JvmSynthetic
internal fun createSingleTexelImageTiles(
    image: DrawImage,
    bounds: IntRect,
): LocalDrawCommand.ComposedBlits? = SingleTexelImageTiles.create(image, bounds)?.let { LocalDrawCommand.ComposedBlits(it, bounds) }

/**
 * Immutable scalar tile description owned by one retained local paint list.
 * Indexed reads recreate the original row-major blits without retaining their command or rectangle objects.
 * Only checked grids whose original terminal increments fit Int coordinates are admitted.
 * These bounds also make every indexed/collapsed origin and edge representable by ordinary Int arithmetic.
 * Fractional presentation reads these original cells, preserving Float/Double validation and sampling.
 */
private class SingleTexelImageTiles private constructor(
    private val image: DrawImage,
    private val bounds: IntRect,
    private val columns: Int,
    override val size: Int,
) : AbstractList<LocalDrawCommand.BlitImage>() {
    override fun get(index: Int): LocalDrawCommand.BlitImage {
        require(index in indices) { "Tile index must be inside the original grid." }
        val left = (index % columns) * image.size.width
        val top = (index / columns) * image.size.height
        val width = minOf(image.size.width, bounds.right - left)
        val height = minOf(image.size.height, bounds.bottom - top)
        return LocalDrawCommand.BlitImage(
            image,
            IntRect(0, 0, width, height),
            IntRect(left, top, left + width, top + height),
        )
    }

    /**
     * Builds only the integer-presentation blits, stretching each provably constant source axis.
     * Other axes retain their tile phase and cropped final source segment.
     */
    fun collapsed(): List<LocalDrawCommand.BlitImage> {
        val tileWidth = if (image.size.width == 1) bounds.width else image.size.width
        val tileHeight = if (image.size.height == 1) bounds.height else image.size.height
        return buildList {
            var top = 0
            while (top < bounds.height) {
                var left = 0
                while (left < bounds.width) {
                    val width = minOf(tileWidth, bounds.width - left)
                    val height = minOf(tileHeight, bounds.height - top)
                    add(
                        LocalDrawCommand.BlitImage(
                            image,
                            IntRect(0, 0, minOf(image.size.width, width), minOf(image.size.height, height)),
                            IntRect(left, top, left + width, top + height),
                        ),
                    )
                    left += tileWidth
                }
                top += tileHeight
            }
        }
    }

    /**
     * Validated construction boundary; rejected grids use the existing scalar producer.
     */
    companion object {
        /**
         * Admits positive singleton-axis grids with representable list size and original terminal increments.
         * No source pixels are read or copied, and no global state or history is retained.
         */
        fun create(
            image: DrawImage,
            bounds: IntRect,
        ): SingleTexelImageTiles? {
            if (bounds.left != 0 || bounds.top != 0) return null
            if (bounds.width == 0 || bounds.height == 0) return null
            if (image.size.width == 0 || image.size.height == 0) return null
            if (image.size.width != 1 && image.size.height != 1) return null
            val columns = (bounds.width.toLong() + image.size.width - 1L) / image.size.width
            val rows = (bounds.height.toLong() + image.size.height - 1L) / image.size.height
            val count = columns * rows
            if (Int.MAX_VALUE < count || Int.MAX_VALUE < columns * image.size.width || Int.MAX_VALUE < rows * image.size.height) return null
            return SingleTexelImageTiles(image, bounds, columns.toInt(), count.toInt())
        }
    }
}
