@file:JvmName("MinecraftUiComponents")
@file:Suppress("FunctionNaming", "TooManyFunctions", "ktlint:standard:function-naming")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.text.UiText

/**
 * Emits one immutable nearest-sampled image component.
 *
 * The source pixels may come from a version adapter's active resource manager, so Mod assets remain replaceable by resource packs without making the retained component platform-specific.
 * The component reports exactly [size] and maps the complete source image to those logical bounds.
 *
 * @receiver active owner-thread UI scope.
 * @param image immutable source pixels retained without a copy.
 * @param size exact logical destination size; defaults to the source pixel size.
 * @param modifier active behavior applied to the image.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the source has an empty axis or later constraints do not contain [size].
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.Image(
    image: DrawImage,
    size: IntSize = image.size,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    element(createMinecraftImageElement(image, IntRect(0, 0, image.size.width, image.size.height), size, modifier, key))
}

/**
 * Emits one immutable nearest-sampled source region from an image.
 *
 * This overload preserves sprite-atlas ownership by retaining the complete immutable image while mapping only [source] to [size].
 * Resource-pack and Mod screens can therefore use a subregion without copying pixels or introducing a purpose-specific component.
 *
 * @receiver active owner-thread UI scope.
 * @param image immutable complete source pixels retained without a copy.
 * @param source nonempty half-open source rectangle contained by [image].
 * @param size exact positive logical destination size; defaults to the source rectangle size.
 * @param modifier active behavior applied to the image.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when [source] is empty or outside [image], [size] has an empty axis, or later constraints do not contain [size].
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.Image(
    image: DrawImage,
    source: IntRect,
    size: IntSize = IntSize(source.width, source.height),
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    element(createMinecraftImageElement(image, source, size, modifier, key))
}

/**
 * Emits one square Minecraft player head from an immutable 64 by 64 skin snapshot.
 *
 * The component reproduces Minecraft 26.2 `PlayerFaceExtractor`: it maps the face region at 8,8 through 16,16 to the complete destination, then optionally maps the hat region at 40,8 through 48,16 over the same pixels.
 * Social Interactions uses the default 24-pixel size, while callers may choose another positive square extent without changing layer order.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param skin immutable complete 64 by 64 player skin retained without a copy.
 * @param size positive logical width and height; defaults to the Social Interactions head size.
 * @param showHat whether the outer hat layer is painted after the face.
 * @param modifier active behavior applied to the head.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when [skin] is not exactly 64 by 64, [size] is not positive, or later constraints do not contain the requested square.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.PlayerHead(
    skin: DrawImage,
    size: Int = 24,
    showHat: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    element(createMinecraftPlayerHeadElement(skin, size, showHat, modifier, key))
}

/**
 * Emits one Minecraft 26.2 container Slot with an optional 16 by 16 item child.
 *
 * The component occupies the exact 18 by 18 pointer region used by `AbstractContainerScreen`, with the item child inset by one pixel.
 * A highlighted Slot paints the native 24 by 24 back layer, then its child, then the native front layer, each overflowing three pixels beyond the component bounds.
 * An enclosing component with [containerBackground] supplies ordinary empty-slot frame pixels; an unhighlighted empty Slot intentionally emits no draw command.
 * Reusable pointer actions are supplied through active modifiers rather than component callbacks.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param highlightable whether pointer hover selects the native highlight layers.
 * @param modifier active behavior applied to the Slot.
 * @param key optional stable identity among direct siblings.
 * @param content optional callback that must emit exactly one 16 by 16 item root when present.
 * @throws IllegalArgumentException when [content] emits zero or multiple roots, its child does not measure to 16 by 16, or later constraints do not contain 18 by 18.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 * @throws Throwable when [content] fails; the exact callback failure escapes unchanged and no Slot description is emitted.
 */
public fun UiScope.Slot(
    highlightable: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    content: (UiScope.() -> Unit)? = null,
) {
    MinecraftProfileImplementation.emitSlot(this, highlightable, modifier, key, content)
}

/**
 * Emits one Minecraft 26.2 Slot synchronized with a declarative inventory binding.
 *
 * [bind] is resolved through the player's current menu, item/count/component changes are polled before every frame, and pointer actions are sent through Minecraft's container-input path rather than mutating inventory storage directly.
 * Item rendering uses the active version adapter's native ItemStack renderer between the Slot's back and front highlight layers.
 * This overload therefore requires a versioned Minecraft platform host and is intentionally unsupported by a portable-only host.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param bind immutable player-inventory, logical Container, or raw active-menu locator created by [MinecraftSlots].
 * @param highlightable whether pointer hover selects the native highlight layers.
 * @param modifier active behavior applied to the Slot before built-in inventory handling.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when [bind] cannot be resolved by the current menu or later constraints do not contain 18 by 18.
 * @throws IllegalStateException when no versioned platform is active, or when used from another thread or outside its active callback.
 * @throws Throwable when synchronized inventory observation, drawing, or container input fails.
 */
public fun UiScope.Slot(
    bind: MinecraftSlotBinding,
    highlightable: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    MinecraftProfileImplementation.emitBoundSlot(this, bind, highlightable, modifier, key)
}

/**
 * Emits one single-line printable-ASCII text component.
 *
 * Only [UiText.Literal] values containing U+0020 through U+007E are accepted.
 * The natural height is nine logical pixels, and the natural width is the checked sum of profile glyph advances with a four-pixel space advance.
 * A later measure requires constraints containing that exact natural size; this component does not clip, wrap, shrink, or substitute unsupported text.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param text unresolved printable-ASCII literal retained unchanged for semantics.
 * @param style typed profile-backed foreground and optional shadow layers.
 * @param modifier active behavior applied to the text.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when the text is not a literal or contains an unsupported code point.
 * @throws ArithmeticException when checked natural-width arithmetic overflows.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.Text(
    text: UiText,
    style: MinecraftTextStyle = MinecraftTextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    MinecraftProfileImplementation.emitText(this, text, style, modifier, key)
}

/**
 * Emits one single-line printable-ASCII literal text component.
 *
 * This convenience overload converts [text] to [UiText.Literal] and otherwise has the same ownership, threading, sizing, and failure behavior as [Text].
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param text printable-ASCII literal retained unchanged for semantics.
 * @param style typed profile-backed foreground and shadow layers.
 * @param modifier active behavior applied to the text.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when [text] contains an unsupported code point.
 * @throws ArithmeticException when checked natural-width arithmetic overflows.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.Text(
    text: String,
    style: MinecraftTextStyle = MinecraftTextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    this.Text(UiText.Literal(text), style, modifier, key)
}

/**
 * Emits one fixed 200 by 20 Minecraft single-line TextField.
 *
 * The field uses the profile's exact normal and highlighted sprites and printable-ASCII font, owns cursor and focus presentation in its retained node, and reads or edits [state] on the owner thread.
 * Keyboard and text-input modifiers run before the built-in editor, so returning `Consumed` overrides default editing while returning `Ignored` permits it.
 * Primary pointer presses acquire focus independently of action consumption and place the cursor using Minecraft glyph advances.
 * The verified editor subset includes insertion, Backspace, Delete, Left, Right, Home, End, pointer cursor placement, and preedit retention; selection, clipboard, word movement, and timed cursor blinking remain outside this component revision.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param state owner-thread text value and maximum length.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed glyph style; [MinecraftTextStyle.TextField] selects ordinary enabled or disabled EditBox colors.
 * @param modifier active behavior applied to the field.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalStateException when the receiver or [state] is used from another thread or outside its active callback.
 */
public fun UiScope.TextField(
    state: MinecraftTextFieldState,
    enabled: Boolean = true,
    textStyle: MinecraftTextStyle = MinecraftTextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    MinecraftProfileImplementation.emitTextField(this, state, IntSize(200, 20), enabled, textStyle, modifier, key)
}

/**
 * Emits one explicitly sized Minecraft single-line TextField.
 *
 * This overload uses the same retained editor, profile sprites, event order, and state ownership as the ordinary 200 by 20 [TextField].
 * Sprite borders use Minecraft-compatible nine-slice mapping, text begins four pixels from the left, and the eight-pixel glyph line is vertically centered with integer-floor placement.
 * Width must retain eight pixels for horizontal text padding and height must contain the eight-pixel font row.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param state owner-thread text value and maximum length.
 * @param size requested logical field extent with both axes at least nine.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed glyph style; [MinecraftTextStyle.TextField] selects ordinary enabled or disabled EditBox colors.
 * @param modifier active behavior applied to the field.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when [size] cannot contain the text and sprite center or later constraints do not contain it.
 * @throws IllegalStateException when the receiver or [state] is used from another thread or outside its active callback.
 */
public fun UiScope.TextField(
    state: MinecraftTextFieldState,
    size: IntSize,
    enabled: Boolean = true,
    textStyle: MinecraftTextStyle = MinecraftTextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    MinecraftProfileImplementation.emitTextField(this, state, size, enabled, textStyle, modifier, key)
}

/**
 * Emits one fixed-height printable-ASCII pointer button.
 *
 * The natural height is exactly 20 logical pixels and constraints must admit the requested width.
 * Width defaults to Minecraft's ordinary 150 pixels, accepts values through the 200-pixel source sprite when all profile borders leave a nonempty center, and the label must fit with two-pixel margins.
 * Pointer movement updates hover only when the host receives an event; a stationary pointer does not create an implicit update.
 * The component owns enabled or disabled semantics and event-driven hover visuals.
 * Reusable pointer actions are supplied through active modifiers such as `Modifier.onPress`, `Modifier.onRelease`, `Modifier.onMove`, `Modifier.onScroll`, and `Modifier.onHover`.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param label unresolved printable-ASCII literal retained for semantics.
 * @param width requested positive logical width through 200; the active profile may impose a larger minimum through its borders.
 * @param enabled whether the button uses enabled semantics and hover visuals.
 * @param modifier active behavior applied to the button.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when [label] is unsupported, does not fit with two-pixel horizontal margins, or [width] is incompatible with the active profile.
 * @throws ArithmeticException when checked label-width arithmetic overflows.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.Button(
    label: UiText,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    MinecraftProfileImplementation.emitButton(this, label, width, enabled, modifier, key)
}

/**
 * Emits one fixed-height printable-ASCII literal pointer button.
 *
 * This convenience overload converts [label] to [UiText.Literal] and otherwise has the same ownership, threading, interaction, sizing, and failure behavior as [Button].
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param label printable-ASCII label shown by the button and exposed as button semantics.
 * @param width requested positive logical width through 200; the active profile may impose a larger minimum through its borders.
 * @param enabled whether the button uses enabled semantics and hover visuals.
 * @param modifier active behavior applied to the button.
 * @param key optional stable identity among direct siblings.
 * @throws IllegalArgumentException when [label] contains an unsupported code point, does not fit with two-pixel horizontal margins, or [width] is incompatible with the active profile.
 * @throws ArithmeticException when checked label-width arithmetic overflows.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 */
public fun UiScope.Button(
    label: String,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    this.Button(UiText.Literal(label), width, enabled, modifier, key)
}

/**
 * Emits one Minecraft 26.2 menu-list scroll viewport containing exactly one root description.
 *
 * Measurement fills finite maximum constraints and measures the content with a bounded width and unbounded height.
 * The content is centered horizontally, begins two logical pixels below the viewport before scrolling, and is clipped to the viewport for paint and pointer hit testing.
 * The component paints the active list texture before its content, then paints the header separator, footer separator, scrollbar track, and scrollbar thumb in native order.
 * Positive logical vertical scroll input moves toward later content, and primary-button scrollbar dragging follows the native proportional displacement while the pointer remains in the viewport.
 *
 * @receiver active owner-thread UI scope supplied by [createMinecraftScreenDefinition] or a nested component callback.
 * @param modifier active behavior applied to the scroll viewport.
 * @param key optional stable identity among direct siblings.
 * @param scrollRate positive logical displacement multiplier; the Language screen uses the default value of nine.
 * @param content callback that must emit exactly one content root.
 * @throws IllegalArgumentException when [scrollRate] is not positive or [content] emits zero or multiple roots.
 * @throws ArithmeticException when checked viewport, content, tiling, or scrollbar arithmetic overflows.
 * @throws IllegalStateException when used from another thread or outside its active callback.
 * @throws Throwable when [content] fails; the exact callback failure escapes unchanged and no Scroll description is emitted.
 */
public fun UiScope.Scroll(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    scrollRate: Int = 9,
    content: UiScope.() -> Unit,
) {
    MinecraftProfileImplementation.emitScroll(this, modifier, key, scrollRate, content)
}
