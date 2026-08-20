package dev.s7a.strata.modifier

import dev.s7a.strata.render.ArgbColor

/**
 * Paints a color across the modifier's complete local bounds before its content.
 * The new behavior is appended inside the existing modifier chain, so existing outer paint behavior remains earlier.
 *
 * @param color the platform-neutral ARGB fill color.
 * @return this chain with one appended background modifier.
 */
public fun Modifier.background(color: ArgbColor): Modifier = then(BackgroundModifier.Element(color))
