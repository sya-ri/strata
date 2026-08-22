@file:JvmName("ImageBackgroundModifiers")

package dev.s7a.strata.modifier

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Paints arbitrary immutable image pixels behind the modified component's content.
 *
 * The image may be loaded from the active resource manager by a version adapter, preserving resource-pack replacement while the retained modifier remains platform-neutral.
 * This behavior does not alter measurement.
 *
 * @receiver immutable modifier chain.
 * @param source immutable pixels or a resource resolved from the active runtime manager.
 * @param scale typed nearest-sampled destination mapping.
 * @return a new chain containing one active background-image node.
 * @throws IllegalArgumentException when the source image has an empty axis.
 * @throws ArithmeticException when tiled destination coordinates overflow.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.imageBackground(
    source: ImageSource,
    scale: ImageScale = ImageScale.Stretch,
): Modifier = ComponentRuntimeBridge.current().imageBackground(this, source, scale)

/**
 * Paints arbitrary immutable image pixels as a Minecraft-compatible nine-slice background behind content.
 *
 * When the destination matches both source axes, paint emits one complete image.
 * When one axis matches, paint emits the native three-segment order; otherwise it emits top-left through bottom-right in row-major order.
 * [NineSliceCenterMode.Tiled] repeats each expandable source segment from its top-left and clips the final tile, while [NineSliceCenterMode.Stretched] maps each complete expandable segment once.
 * Destination borders are clamped independently to half of the destination axis exactly like Minecraft 26.2.
 * This behavior does not alter measurement.
 *
 * @receiver immutable modifier chain.
 * @param source immutable pixels or a resource resolved from the active runtime manager.
 * @param border non-negative source border widths that leave a nonempty center on both axes.
 * @param centerMode typed mapping for expandable edges and the center.
 * @return a new chain containing one active nine-slice background node.
 * @throws IllegalArgumentException when the source image has an empty axis or the borders consume a source center.
 * @throws ArithmeticException when source validation, segment, or tiled destination arithmetic overflows.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.imageBackground(
    source: ImageSource,
    border: Insets,
    centerMode: NineSliceCenterMode = NineSliceCenterMode.Tiled,
): Modifier = ComponentRuntimeBridge.current().imageBackground(this, source, border, centerMode)

/**
 * Paints the selected Minecraft menu texture behind the modified component's content.
 *
 * Paint repeats the 16 by 16 profile image as nearest-sampled 32 by 32 logical tiles in row-major order and preserves overflowing edge tiles.
 * This behavior does not alter measurement and is available only while a Minecraft screen-content callback is active on its owner thread.
 *
 * @receiver immutable modifier chain extended inside the active screen callback.
 * @return a new chain retaining the immutable menu asset in one active paint modifier.
 * @throws IllegalStateException when used from another thread or outside the callback.
 * @throws ArithmeticException when a final overflowing tile edge is not representable during paint.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.menuBackground(): Modifier = ComponentRuntimeBridge.current().menuBackground(this)

/**
 * Sizes the modified component to a Minecraft 26.2 generic chest panel and paints its background before content.
 *
 * The natural size is 176 by `114 + rows * 18` logical pixels.
 * Paint emits the same upper and lower source regions from `generic_54.png` in the same order as `ContainerScreen.extractBackground`; the final logical row remains outside those two native blits.
 * This behavior is available only while a Minecraft screen-content callback is active on its owner thread.
 *
 * @receiver immutable modifier chain extended inside the active screen callback.
 * @param rows chest row count from one through six.
 * @return a new chain retaining the immutable container asset and row policy in one active measure-and-paint modifier.
 * @throws IllegalArgumentException when [rows] is outside one through six or later constraints do not contain the exact natural size.
 * @throws ArithmeticException when checked size arithmetic overflows.
 * @throws IllegalStateException when used from another thread or outside the callback.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.containerBackground(
    rows: Int = 3,
): Modifier = ComponentRuntimeBridge.current().containerBackground(this, rows)
