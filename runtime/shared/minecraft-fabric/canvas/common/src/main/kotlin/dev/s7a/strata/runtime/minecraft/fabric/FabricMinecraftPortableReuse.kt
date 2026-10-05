package dev.s7a.strata.runtime.minecraft.fabric

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
    val matches = IntArray(next.size) { -1 }
    var prefix = 0
    while (prefix < minOf(previous.size, next.size) && previous[prefix].equivalent(next[prefix])) {
        matches[prefix] = prefix
        prefix += 1
    }
    var source = previous.lastIndex
    var destination = next.lastIndex
    while (prefix <= source && prefix <= destination && previous[source].equivalent(next[destination])) {
        matches[destination] = source
        source -= 1
        destination -= 1
    }
    for (index in prefix..destination) {
        if (previous.getOrNull(index)?.equivalent(next[index]) == true) matches[index] = index
    }
    return matches
}
