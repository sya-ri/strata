package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.Insets

/**
 * Adds checked distances around the virtual child.
 * The new behavior is appended inside the existing modifier chain.
 * Measurement reports an [ArithmeticException] if a measured child extent plus the checked inset total overflows.
 *
 * @param insets the distances applied to each side.
 * @return this chain with one appended padding modifier.
 */
public fun Modifier.padding(insets: Insets): Modifier = then(PaddingModifier.Element(insets))

/**
 * Adds one distance around every side of the virtual child.
 * The new behavior is appended inside the existing modifier chain.
 *
 * @param all the non-negative distance applied to every side.
 * @return this chain with one appended padding modifier.
 * @throws IllegalArgumentException when [all] is negative.
 * @throws ArithmeticException when twice [all] cannot be represented as an [Int].
 */
public fun Modifier.padding(all: Int): Modifier = padding(Insets.all(all))

/**
 * Adds equal horizontal and equal vertical distances around the virtual child.
 * The new behavior is appended inside the existing modifier chain.
 *
 * @param horizontal the non-negative distance applied to the left and right sides.
 * @param vertical the non-negative distance applied to the top and bottom sides.
 * @return this chain with one appended padding modifier.
 * @throws IllegalArgumentException when either distance is negative.
 * @throws ArithmeticException when twice either distance cannot be represented as an [Int].
 */
public fun Modifier.padding(
    horizontal: Int,
    vertical: Int,
): Modifier = padding(Insets.symmetric(horizontal, vertical))
