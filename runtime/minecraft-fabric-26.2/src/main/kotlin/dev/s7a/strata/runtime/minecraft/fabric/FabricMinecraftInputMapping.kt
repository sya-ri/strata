package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import kotlin.math.floor

/**
 * Converts a finite native GUI coordinate to the common integer coordinate.
 *
 * @param value native floating-point coordinate.
 * @return the floored coordinate, or `null` when the value is non-finite or outside the integer domain.
 */
@JvmSynthetic
internal fun mapMinecraftCoordinate(value: Double): Int? {
    if (value.isFinite().not()) return null
    val coordinate = floor(value)
    if (coordinate < Int.MIN_VALUE.toDouble() || Int.MAX_VALUE.toDouble() < coordinate) return null
    return coordinate.toInt()
}

/**
 * Converts one native GUI position to the common integer coordinate space.
 *
 * @param x native floating-point x coordinate.
 * @param y native floating-point y coordinate.
 * @return the independently floored position, or `null` when either coordinate is invalid.
 */
@JvmSynthetic
internal fun mapMinecraftPosition(
    x: Double,
    y: Double,
): IntOffset? {
    val mappedX = mapMinecraftCoordinate(x) ?: return null
    val mappedY = mapMinecraftCoordinate(y) ?: return null
    return IntOffset(mappedX, mappedY)
}

/**
 * Converts one native mouse button number to the typed common pointer button.
 *
 * @param button native GLFW-style button number.
 * @return the typed button, or `null` for an unsupported negative button.
 */
@JvmSynthetic
internal fun mapMinecraftButton(button: Int): PointerButton? =
    when (button) {
        0 -> PointerButton.Primary
        1 -> PointerButton.Secondary
        2 -> PointerButton.Middle
        else -> if (2 < button) PointerButton.Auxiliary(button - 3) else null
    }

/**
 * Maps native vertical scroll into the common increasing-y direction.
 *
 * @param nativeDeltaY native Minecraft vertical scroll displacement.
 * @return the common vertical displacement.
 */
@JvmSynthetic
internal fun mapMinecraftVerticalScroll(nativeDeltaY: Double): Double = -nativeDeltaY

/**
 * Maps one native scroll pair when both displacements are finite.
 *
 * @param nativeDeltaX native horizontal displacement.
 * @param nativeDeltaY native vertical displacement.
 * @return normalized horizontal and vertical displacement, or `null` for invalid native input.
 */
@JvmSynthetic
internal fun mapMinecraftScroll(
    nativeDeltaX: Double,
    nativeDeltaY: Double,
): Pair<Double, Double>? {
    if (nativeDeltaX.isFinite().not() || nativeDeltaY.isFinite().not()) return null
    return nativeDeltaX to mapMinecraftVerticalScroll(nativeDeltaY)
}
