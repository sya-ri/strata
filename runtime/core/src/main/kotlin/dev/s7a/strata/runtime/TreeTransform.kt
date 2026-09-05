package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.ChildTransform
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Accumulated positive uniform transform from one retained entry's local coordinates into root coordinates.
 *
 * The runtime owns this immutable value on the tree thread. Coordinates remain continuous until an API contract requires
 * integer bounds or pointer positions.
 *
 * @property scale finite positive local-to-root scale.
 * @property offset finite root-coordinate position of the local origin.
 */
internal data class TreeTransform(
    val scale: Double,
    val offset: DoubleOffset,
) {
    init {
        require(scale.isFinite() && 0.0 < scale) { "Tree transform scale must be finite and positive." }
    }

    /**
     * Composes one placed direct-child transform after this local-to-root transform.
     *
     * The child-local position is scaled first. The child offset and ordinary integer placement are then added in this
     * entry's local coordinates before applying this accumulated parent transform.
     *
     * @param placement ordinary child placement in this entry's local coordinates.
     * @param child transform from child-local coordinates relative to that placement.
     * @return accumulated child-local to root transform.
     * @throws IllegalArgumentException when composed arithmetic is not finite and positive.
     */
    fun descend(
        placement: IntOffset,
        child: ChildTransform,
    ): TreeTransform =
        TreeTransform(
            scale = scale * child.scale,
            offset =
                DoubleOffset(
                    offset.x + scale * (placement.x.toDouble() + child.offset.x),
                    offset.y + scale * (placement.y.toDouble() + child.offset.y),
                ),
        )

    /**
     * Projects one local integer rectangle to an enclosing root-coordinate rectangle.
     *
     * Nonempty axes round outward. Empty axes remain empty at the floored transformed edge.
     *
     * @param local local half-open rectangle.
     * @return checked enclosing root-coordinate rectangle.
     * @throws IllegalArgumentException when floating-point geometry cannot represent a nonempty projected extent.
     * @throws ArithmeticException when a projected edge is outside the Int coordinate range.
     */
    fun enclosing(local: IntRect): IntRect {
        val leftValue = mapX(local.left.toDouble())
        val topValue = mapY(local.top.toDouble())
        val rightValue = mapX(local.right.toDouble())
        val bottomValue = mapY(local.bottom.toDouble())
        require(local.width == 0 || leftValue < rightValue) {
            "Transformed horizontal bounds extent must be representable as a Double."
        }
        require(local.height == 0 || topValue < bottomValue) {
            "Transformed vertical bounds extent must be representable as a Double."
        }
        val left = checkedFloor(leftValue)
        val top = checkedFloor(topValue)
        val right = if (local.width == 0) left else checkedCeil(rightValue)
        val bottom = if (local.height == 0) top else checkedCeil(bottomValue)
        return IntRect(left, top, right, bottom)
    }

    /**
     * Projects one local measured size to enclosing root-coordinate bounds.
     *
     * @param size non-negative local measured size.
     * @return checked root-coordinate bounds.
     * @throws IllegalArgumentException when floating-point geometry cannot represent a nonempty projected extent.
     * @throws ArithmeticException when a projected edge is outside the Int coordinate range.
     */
    fun enclosing(size: IntSize): IntRect = enclosing(IntRect(0, 0, size.width, size.height))

    /**
     * Projects one local integer rectangle without quantizing its transformed edges.
     *
     * @param local local half-open rectangle.
     * @return finite fractional root-coordinate rectangle.
     * @throws IllegalArgumentException when floating-point geometry cannot represent a projected extent.
     */
    fun mapFractional(local: IntRect): FloatRect =
        floatRect(
            mapX(local.left.toDouble()),
            mapY(local.top.toDouble()),
            mapX(local.right.toDouble()),
            mapY(local.bottom.toDouble()),
            horizontalNonempty = 0 < local.width,
            verticalNonempty = 0 < local.height,
        )

    /**
     * Projects one local fractional rectangle without quantizing its transformed edges.
     *
     * @param local local half-open rectangle.
     * @return finite fractional root-coordinate rectangle.
     * @throws IllegalArgumentException when floating-point geometry cannot represent a projected extent.
     */
    fun mapFractional(local: FloatRect): FloatRect =
        floatRect(
            mapX(local.left.toDouble()),
            mapY(local.top.toDouble()),
            mapX(local.right.toDouble()),
            mapY(local.bottom.toDouble()),
            horizontalNonempty = 0f < local.width,
            verticalNonempty = 0f < local.height,
        )

    /**
     * Tests one root-coordinate point against exact transformed local measured bounds.
     *
     * @param size non-negative local measured size.
     * @param position root-coordinate pointer position.
     * @return true when the inverse-mapped point is inside both half-open local axes.
     */
    fun contains(
        size: IntSize,
        position: IntOffset,
    ): Boolean {
        val localX = (position.x.toDouble() - offset.x) / scale
        val localY = (position.y.toDouble() - offset.y) / scale
        return 0.0 <= localX && localX < size.width.toDouble() && 0.0 <= localY && localY < size.height.toDouble()
    }

    /**
     * Inverse-maps one root-coordinate pointer position and floors it to the containing local logical cell.
     *
     * The result is intentionally not clamped so captured pointer delivery preserves out-of-bounds coordinates.
     *
     * @param position root-coordinate pointer position.
     * @return floored local logical position.
     * @throws ArithmeticException when an inverse-mapped coordinate is outside the Int range.
     */
    fun localPosition(position: IntOffset): IntOffset =
        IntOffset(
            checkedFloor((position.x.toDouble() - offset.x) / scale),
            checkedFloor((position.y.toDouble() - offset.y) / scale),
        )

    /**
     * Returns the exact integer translation represented by this transform when no scaling or fractional translation is active.
     *
     * @return integer translation, or null when portable command conversion is required.
     * @throws ArithmeticException when an integral translation is outside the Int range.
     */
    fun integerTranslationOrNull(): IntOffset? {
        if (scale != 1.0 || offset.x != floor(offset.x) || offset.y != floor(offset.y)) return null
        return IntOffset(checkedIntegral(offset.x), checkedIntegral(offset.y))
    }

    private fun mapX(value: Double): Double = offset.x + scale * value

    private fun mapY(value: Double): Double = offset.y + scale * value

    private fun floatRect(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        horizontalNonempty: Boolean,
        verticalNonempty: Boolean,
    ): FloatRect {
        require(horizontalNonempty.not() || left < right) {
            "Transformed horizontal drawing extent must be representable as a Double."
        }
        require(verticalNonempty.not() || top < bottom) {
            "Transformed vertical drawing extent must be representable as a Double."
        }
        val result =
            FloatRect(
                checkedFloat(left),
                checkedFloat(top),
                checkedFloat(right),
                checkedFloat(bottom),
            )
        require(horizontalNonempty.not() || result.left < result.right) {
            "Transformed horizontal drawing extent must be representable as a Float."
        }
        require(verticalNonempty.not() || result.top < result.bottom) {
            "Transformed vertical drawing extent must be representable as a Float."
        }
        return result
    }

    /**
     * Common accumulated transforms.
     */
    companion object {
        /**
         * Root transform that preserves logical coordinates.
         */
        val Identity: TreeTransform = TreeTransform(1.0, DoubleOffset.Zero)
    }
}

private fun checkedFloat(value: Double): Float {
    val converted = value.toFloat()
    require(converted.isFinite()) { "Transformed drawing coordinate must be representable as a Float." }
    return converted
}

private fun checkedFloor(value: Double): Int = checkedIntegral(floor(value))

private fun checkedCeil(value: Double): Int = checkedIntegral(ceil(value))

private fun checkedIntegral(value: Double): Int {
    if (value < Int.MIN_VALUE.toDouble() || Int.MAX_VALUE.toDouble() < value) {
        throw ArithmeticException("Transformed coordinate exceeds the Int range.")
    }
    return value.toInt()
}
