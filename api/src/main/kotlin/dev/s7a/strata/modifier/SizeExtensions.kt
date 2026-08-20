package dev.s7a.strata.modifier

/**
 * Constrains both axes to exact requested sizes after clamping them into the parent constraints.
 * The new behavior is appended inside the existing modifier chain.
 *
 * @param width the requested non-negative width.
 * @param height the requested non-negative height.
 * @return this chain with one appended size modifier.
 * @throws IllegalArgumentException when either requested size is negative.
 */
public fun Modifier.size(
    width: Int,
    height: Int,
): Modifier = then(SizeModifier.Element(AxisConstraint.Exact(width), AxisConstraint.Exact(height)))

/**
 * Constrains the width to an exact requested size after clamping it into the parent constraints.
 * The new behavior is appended inside the existing modifier chain and leaves the height policy unchanged.
 *
 * @param width the requested non-negative width.
 * @return this chain with one appended width modifier.
 * @throws IllegalArgumentException when [width] is negative.
 */
public fun Modifier.width(width: Int): Modifier = then(SizeModifier.Element(AxisConstraint.Exact(width), AxisConstraint.Unchanged))

/**
 * Constrains the height to an exact requested size after clamping it into the parent constraints.
 * The new behavior is appended inside the existing modifier chain and leaves the width policy unchanged.
 *
 * @param height the requested non-negative height.
 * @return this chain with one appended height modifier.
 * @throws IllegalArgumentException when [height] is negative.
 */
public fun Modifier.height(height: Int): Modifier = then(SizeModifier.Element(AxisConstraint.Unchanged, AxisConstraint.Exact(height)))

/**
 * Constrains both axes to independently clamped inclusive ranges.
 * A requested range disjoint from its parent range is pinned to the nearest parent boundary.
 * [Int.MAX_VALUE] remains an unbounded maximum, and the new behavior is appended inside the existing modifier chain.
 *
 * @param minWidth the non-negative requested minimum width.
 * @param minHeight the non-negative requested minimum height.
 * @param maxWidth the requested maximum width.
 * @param maxHeight the requested maximum height.
 * @return this chain with one appended range modifier.
 * @throws IllegalArgumentException when a value is negative or a minimum exceeds its corresponding maximum.
 */
public fun Modifier.sizeIn(
    minWidth: Int = 0,
    minHeight: Int = 0,
    maxWidth: Int = Int.MAX_VALUE,
    maxHeight: Int = Int.MAX_VALUE,
): Modifier =
    then(
        SizeModifier.Element(
            AxisConstraint.Range(minWidth, maxWidth),
            AxisConstraint.Range(minHeight, maxHeight),
        ),
    )

/**
 * Constrains the width to an independently clamped inclusive range.
 * A requested range disjoint from its parent range is pinned to the nearest parent boundary.
 * [Int.MAX_VALUE] remains an unbounded maximum, and the new behavior is appended inside the existing modifier chain.
 *
 * @param min the non-negative requested minimum width.
 * @param max the requested maximum width.
 * @return this chain with one appended width-range modifier.
 * @throws IllegalArgumentException when a value is negative or [min] exceeds [max].
 */
public fun Modifier.widthIn(
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
): Modifier = then(SizeModifier.Element(AxisConstraint.Range(min, max), AxisConstraint.Unchanged))

/**
 * Constrains the height to an independently clamped inclusive range.
 * A requested range disjoint from its parent range is pinned to the nearest parent boundary.
 * [Int.MAX_VALUE] remains an unbounded maximum, and the new behavior is appended inside the existing modifier chain.
 *
 * @param min the non-negative requested minimum height.
 * @param max the requested maximum height.
 * @return this chain with one appended height-range modifier.
 * @throws IllegalArgumentException when a value is negative or [min] exceeds [max].
 */
public fun Modifier.heightIn(
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
): Modifier = then(SizeModifier.Element(AxisConstraint.Unchanged, AxisConstraint.Range(min, max)))

/**
 * Fills every bounded parent axis while preserving unbounded axes.
 * The new behavior is appended inside the existing modifier chain.
 *
 * @return this chain with one appended fill modifier.
 */
public fun Modifier.fillMaxSize(): Modifier = then(SizeModifier.Element(AxisConstraint.Fill, AxisConstraint.Fill))

/**
 * Fills a bounded parent width while preserving an unbounded width.
 * The new behavior is appended inside the existing modifier chain and leaves the height policy unchanged.
 *
 * @return this chain with one appended width-fill modifier.
 */
public fun Modifier.fillMaxWidth(): Modifier = then(SizeModifier.Element(AxisConstraint.Fill, AxisConstraint.Unchanged))

/**
 * Fills a bounded parent height while preserving an unbounded height.
 * The new behavior is appended inside the existing modifier chain and leaves the width policy unchanged.
 *
 * @return this chain with one appended height-fill modifier.
 */
public fun Modifier.fillMaxHeight(): Modifier = then(SizeModifier.Element(AxisConstraint.Unchanged, AxisConstraint.Fill))
