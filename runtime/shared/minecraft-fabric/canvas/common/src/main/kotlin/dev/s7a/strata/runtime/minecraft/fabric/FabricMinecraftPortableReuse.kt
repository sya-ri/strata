@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Matches unchanged prefix and suffix layers across insertion, removal or replacement in one ordered portable list.
 *
 * Remaining layers may reuse the same previous index. Matching is linear in layer count and never hashes source pixels.
 * The returned indices refer only to the immediately previous generation; negative entries require rasterization.
 */
@JvmSynthetic
internal fun matchFabricMinecraftPortableImages(
    previous: List<FabricMinecraftPortableImage>,
    next: List<FabricMinecraftPortableImage>,
): IntArray {
    return matchPortableImages(previous, next, FabricMinecraftPortableImage::equivalent)
}

/**
 * Matches exact current CPU inputs before metadata allocation, with one shared 8,192-command/key comparison allowance.
 * The complete key includes original size, scale, origin, command order/value and referential image identity.
 * Only the previous list is borrowed; results contain indexes and never retain a prior-frame chain.
 * Exhaustion is an ordinary miss, preserving exact construction and the new traversal's independent admission budget.
 */
@JvmSynthetic
internal fun matchFabricMinecraftPreparedInputs(
    previous: List<FabricMinecraftPortableImage>,
    next: List<FabricMinecraftPortableImage>,
): IntArray {
    var remaining = 8_192L
    return matchPortableImages(previous, next) { a, b ->
        val cost = if (a.commands === b.commands) 1L else b.commands.size.toLong() + 1L
        if (remaining < cost) {
            false
        } else {
            remaining -= cost
            a.samePreparedInputs(b)
        }
    }
}

/**
 * Finds same-index axis-copy opportunities only for complete-map misses, under a separate shared 8,192-unit allowance.
 * Comparison returns indexes only and borrows no preceding command/source/map beyond this synchronous traversal.
 * Complete hits are never reconsidered, so a rejected current admission cannot bypass the new ledger through axis copying.
 */
@JvmSynthetic
internal fun matchFabricMinecraftPreparedAxes(
    previous: List<FabricMinecraftPortableImage>,
    next: List<FabricMinecraftPortableImage>,
    complete: IntArray,
): IntArray {
    require(complete.size == next.size)
    var remaining = 8_192L
    return IntArray(next.size) { index ->
        val old = previous.getOrNull(index)
        if (0 <= complete[index] || old?.composition == null) {
            -1
        } else {
            val input = next[index]
            val cost = if (input.commands === old.commands) 1L else input.commands.size.toLong() + 1L
            if (remaining < cost) {
                -1
            } else {
                remaining -= cost
                if (input.samePreparedAxes(old)) index else -1
            }
        }
    }
}

/**
 * Proves exact original CPU inputs without DrawImage value equality, pixel reads or derived metadata comparison.
 * Immutable list identity is a shortcut; new lists use the exhaustive primitive visitor and exact image identity.
 */
@JvmSynthetic
internal fun FabricMinecraftPortableImage.samePreparedInputs(other: FabricMinecraftPortableImage): Boolean {
    if (size != other.size || scale != other.scale || origin != other.origin) return false
    if (commands === other.commands) return true
    if (commands.size != other.commands.size) return false
    return commands.indices.all { samePreparedCommand(commands[it], other.commands[it]) }
}

/**
 * Proves identical original axis/coverage inputs while allowing new colors, cutoffs and same-extent source identities.
 * Command kinds/order, original geometry/clips, scale/origin and immutable source dimensions remain exact.
 * The caller bounds comparison and uses this proof only to copy old immutable axes into a fresh exclusively owned index image.
 * Current controls, factors and source ownership are rebuilt; this proof never admits reuse of an old complete output.
 */
@JvmSynthetic
internal fun FabricMinecraftPortableImage.samePreparedAxes(other: FabricMinecraftPortableImage): Boolean {
    if (size != other.size || scale != other.scale || origin != other.origin) return false
    if (commands === other.commands) return true
    if (commands.size != other.commands.size) return false
    return commands.indices.all { sameAxisCommand(commands[it], other.commands[it]) }
}

private inline fun matchPortableImages(
    previous: List<FabricMinecraftPortableImage>,
    next: List<FabricMinecraftPortableImage>,
    same: (FabricMinecraftPortableImage, FabricMinecraftPortableImage) -> Boolean,
): IntArray {
    val matches = IntArray(next.size) { -1 }
    var prefix = 0
    while (prefix < minOf(previous.size, next.size) && same(previous[prefix], next[prefix])) {
        matches[prefix] = prefix
        prefix += 1
    }
    var source = previous.lastIndex
    var destination = next.lastIndex
    while (prefix <= source && prefix <= destination && same(previous[source], next[destination])) {
        matches[destination] = source
        source -= 1
        destination -= 1
    }
    for (index in prefix..destination) {
        val old = previous.getOrNull(index)
        if (old != null && same(old, next[index])) matches[index] = index
    }
    return matches
}

private fun samePreparedCommand(
    a: DrawCommand,
    b: DrawCommand,
): Boolean =
    when (a) {
        is DrawCommand.FillRectangle -> b is DrawCommand.FillRectangle && a.bounds == b.bounds && a.isFabricMinecraftCompositionNoOp() == b.isFabricMinecraftCompositionNoOp() && a.color == b.color
        is DrawCommand.BlitImage -> samePreparedBlit(a, b)
        is DrawCommand.BlitImagePixels -> samePreparedPixelBlit(a, b)
        is DrawCommand.SampledImage -> samePreparedSample(a, b)
        is DrawCommand.PushClip -> b is DrawCommand.PushClip && a.bounds == b.bounds
        is DrawCommand.PushFractionalClip -> b is DrawCommand.PushFractionalClip && a.bounds == b.bounds
        DrawCommand.PopClip -> b === DrawCommand.PopClip
        is DrawCommand.Platform -> false
    }

private fun samePreparedBlit(
    a: DrawCommand.BlitImage,
    b: DrawCommand,
): Boolean {
    if (b is DrawCommand.BlitImage) {
        val sameSource = a.image === b.image && a.source == b.source
        return sameSource && a.destination == b.destination
    }
    return false
}

private fun samePreparedPixelBlit(
    a: DrawCommand.BlitImagePixels,
    b: DrawCommand,
): Boolean {
    if (b is DrawCommand.BlitImagePixels) {
        val sameSource = a.image === b.image && a.source == b.source
        return sameSource && a.destination == b.destination
    }
    return false
}

private fun samePreparedSample(
    a: DrawCommand.SampledImage,
    b: DrawCommand,
): Boolean {
    if (b is DrawCommand.SampledImage) {
        if (a.image !== b.image || a.source != b.source || a.destination != b.destination) return false
        return a.tint == b.tint && a.alphaCutoff.toRawBits() == b.alphaCutoff.toRawBits() && a.orientation == b.orientation
    }
    return false
}

@Suppress("CyclomaticComplexMethod") // Each primitive's exact original axis key is independent of current color/source ownership controls.
private fun sameAxisCommand(
    a: DrawCommand,
    b: DrawCommand,
): Boolean =
    when (a) {
        is DrawCommand.FillRectangle -> b is DrawCommand.FillRectangle && a.bounds == b.bounds
        is DrawCommand.BlitImage -> {
            if (b is DrawCommand.BlitImage) {
                val source = a.image.size == b.image.size && a.source == b.source
                source && a.destination == b.destination
            } else {
                false
            }
        }

        is DrawCommand.BlitImagePixels -> {
            if (b is DrawCommand.BlitImagePixels) {
                val source = a.image.size == b.image.size && a.source == b.source
                source && a.destination == b.destination
            } else {
                false
            }
        }

        is DrawCommand.SampledImage -> {
            if (b is DrawCommand.SampledImage) {
                val geometry = a.image.size == b.image.size && a.source == b.source && a.destination == b.destination
                geometry && a.orientation == b.orientation && a.isFabricMinecraftCompositionNoOp() == b.isFabricMinecraftCompositionNoOp()
            } else {
                false
            }
        }

        is DrawCommand.PushClip -> b is DrawCommand.PushClip && a.bounds == b.bounds
        is DrawCommand.PushFractionalClip -> b is DrawCommand.PushFractionalClip && a.bounds == b.bounds
        DrawCommand.PopClip -> b === DrawCommand.PopClip
        is DrawCommand.Platform -> false
    }
