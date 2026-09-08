# Text and editing

Use `Text` for display, `TextField` for one editable line, and `TextArea` for an editable multiline value.
Labels and editors share font selection, while caller-owned state holds committed editor values.
The [component overview](../reference/components.md) contains their images and compiled examples, and the [API reference](https://gh.s7a.dev/strata/) lists all overloads.

Text can contain Japanese, Korean, and supplementary Unicode characters such as emoji when the selected resource pack supplies the glyphs.
The active profile supplies both metrics and pixels, so the same font choice controls layout and drawing.
Strata does not add an operating-system font, color-emoji, or ZWJ-sequence renderer.

## Selecting a font

Pass `font = ResourceId("example", "body")` to `Text`, `TextField`, or `TextArea` to select `assets/example/font/body.json` from the resource pack.
The ID names a font definition, not an operating-system font family or a direct TTF file.
Without an explicit selection, the requested font is `minecraft:default`.
`TextStyle` still chooses the component's color and shadow treatment independently of its font.
Legacy section-sign formatting codes remain unsupported; use the component style and explicit font wrappers instead.

Use `UiText.withFont` when the font belongs to a reusable label or one part of `UiText.concat`.
Font selection is inherited through composition: a nested `withFont` takes precedence over an outer wrapper or the `Text` font argument.
An outer selection does not overwrite an explicitly styled child.
The wrapper retains only an immutable resource ID and does not resolve or own native resources.
Display shaping and bidirectional ordering preserve each glyph's original logical font selection, including after Arabic contractions and around supplementary scalars.
A Lam-Alef ligature uses the Lam's selection; following text keeps its own original selection rather than inheriting a contracted string offset.
Multiline layout uses the same provenance and retains the innermost selection even for empty content.

This complete example is compiled against `api` alone in `integration:api`.
The documentation test compares this block with its marked source in `ApiOnlyUnicodeTextScreen.kt`.
The caller creates `TextFieldState` on the host thread and supplies a pack containing `example:body`, or passes another font ID.

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Creates a Unicode text screen using a caller-supplied resource-pack font.
 *
 * The returned definition is unevaluated and must be opened or closed once.
 * Its host must use a font-resource profile containing the selected font.
 *
 * @param state caller-owned field state created on the host's owner thread.
 * @param font resource identifier of the font definition supplied by the active pack.
 * @return one-shot screen definition that retains the state without changing its value.
 */
internal fun unicodeTextScreen(
    state: TextFieldState,
    font: ResourceId = ResourceId("example", "body"),
): ScreenDefinition {
    val heading = UiText.Literal("日本語 한국어 🙂").withFont(font)
    return ScreenDefinition("Unicode text") {
        Column(spacing = 6) {
            Text(heading)
            Text("同じフォント / 같은 글꼴", font = font)
            TextField(state, font = font)
        }
    }
}
```

The existing overloads without a font argument remain available, including the fixed-size and explicit-size `TextField` forms.
No font objects or rendering implementation are needed in application declarations.
See [Font resources](../guides/fonts.md) for profile snapshots, offline resource loading, and native backend configuration.
The older profile builder that accepts a finite printable-ASCII glyph table remains available for compatibility; that table alone cannot render arbitrary Unicode or custom fonts.

## Multiline display

Existing `Text` overloads and `TextLayout.SingleLine` remain strict single-line display.
They reject LF, CR, VT, FF, NEL, line separator, and paragraph separator.
Pass a required `TextLayout.Multiline` argument to accept those hard breaks; CRLF is one break, including when the two code units belong to different font wrappers.
The original UiText, including hard breaks and omitted content, remains the semantic label.

Multiline Text measures against the maximum width supplied by its parent.
No absolute position or required width argument is introduced; ordinary layout constraints and modifiers choose the available space.
`TextWrap.None` uses hard breaks only, `TextWrap.Character` wraps at Unicode scalar boundaries, and `TextWrap.Word` prefers breakable whitespace before falling back to scalar boundaries for overlong segments such as Japanese text without spaces.
This is a limited whitespace policy, not a language-specific line-breaking engine.
NBSP, figure space, and narrow NBSP are not preferred break opportunities, although an overlong unbroken segment can still use scalar fallback.
Leading, repeated, and trailing spaces remain part of the text and its insertion offsets.

Each line has a logical height of nine pixels; non-negative `lineSpacing` adds space between lines.
`maxLines` omits subsequent runs without cutting legal ink overhang from the last visible line.
`TextOverflow.Clip` uses the actual constrained viewport; `TextOverflow.Ellipsis` appends `...` only when the selected font's marker fits, otherwise it keeps the Clip result without a marker.
Natural, unconstrained text preserves glyph bearings, shadow extents, and ink outside the logical line box.
Finite or exact viewport restrictions clip at that viewport, including a partially visible first line for any positive height; a zero-height viewport displays no lines.

## Multiline editing

Use `TextArea` for an editable multiline value, such as composing a message or writing notes.
It shares the display line-breaking primitive but keeps editing in logical scalar order.
`TextAreaState` is caller-owned and confined to its constructing thread, with a positive UTF-16 `maxLength` defaulting to 32,767.
Its committed value normalizes CRLF, CR, VT, FF, NEL, line separator, and paragraph separator to LF before applying that limit.
Other C0 controls, DEL, isolated surrogates, and the section-sign formatting marker are rejected atomically.

Choose `TextAreaViewport.Lines(width, lines)` for a positive outer width and visible row count, or `TextAreaViewport.Size(IntSize(...))` for an explicit outer size.
The Minecraft frame has four-pixel insets on each side, so the Lines height is `8 + 9 * lines + lineSpacing * (lines - 1)`.
The requested size must leave a positive inner viewport and fit its parent's constraints.
Line boxes use the same nine-pixel logical height even when a custom glyph's ink extends beyond them.

The state owns one stable `ScrollState` for its lifetime.
Link an optional external `Scrollbar(state.scrollState)` to that position; the editor owns the geometry while attached, while application scroll writes and other observers remain independent.
One TextAreaState permits one attached editor, and a second simultaneous attachment fails without stealing the first subscription.
Immutable element descriptions do not retain a live editor or host callback and can be reused after detach; independent states permit independent simultaneous editors.

The following complete example is compiled against the API alone.
The two states belong to the caller and must be distinct and created on the host thread.

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.width
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText

/**
 * Creates message and notes editors while compiling against the API artifact alone.
 *
 * Both editor states belong to the caller and must be distinct and created on the host's owner thread.
 * Scrollbars are independent siblings sharing each editor's vertical position.
 * The returned definition remains unevaluated until a compatible runtime transfers or closes it.
 *
 * @param message caller-owned multiline message being composed.
 * @param notes independent caller-owned notes value.
 * @param font resource-pack font available in the eventual runtime profile.
 * @return a one-shot definition that retains neither native fonts nor an attached editor.
 */
internal fun multilineTextScreen(
    message: TextAreaState,
    notes: TextAreaState,
    font: ResourceId,
): ScreenDefinition =
    ScreenDefinition("Multiline text") {
        Column(spacing = 6) {
            Text(UiText.Literal("日本語\n한국어 🙂"), TextLayout.Multiline(), modifier = Modifier.Empty.width(240))
            Text("Message composition", TextLayout.SingleLine, font)
            Row(spacing = 4) {
                TextArea(message, TextAreaViewport.Lines(width = 240, lines = 4), font)
                Scrollbar(message.scrollState, modifier = Modifier.Empty.height(44))
            }
            Text(UiText.Literal("Notes\nメモ"), TextLayout.Multiline(maxLines = 2, overflow = TextOverflow.Ellipsis), font)
            Text("Independent scrolling", TextLayout.SingleLine)
            Row(spacing = 4) {
                TextArea(notes, TextAreaViewport.Size(IntSize(240, 96)), wrap = TextWrap.None, lineSpacing = 1)
                Scrollbar(notes.scrollState, modifier = Modifier.Empty.height(96))
            }
        }
    }
```

Enter inserts LF; Left and Right traverse scalar positions and both visual sides of a soft-wrap boundary.
Home and End select the current visual line edges; Control or Super selects the document edges.
Up, Down, PageUp, and PageDown preserve the preferred horizontal column, with page movement based on the current viewport and line spacing.
Pointer placement uses the current scrolled layout; a spacing gap belongs to the nearer nine-pixel line box, with an exact midpoint assigned to the preceding line.
At a shared soft-wrap offset the cursor keeps upstream or downstream affinity so an end-of-line click or vertical move does not jump to the following line.
Edits and external value changes reset affinity; reflow preserves it when the corresponding soft boundary remains.
An exact-fit line-end caret paints at the final inner pixel without changing glyph metrics or wrapping.
Tab remains focus navigation rather than inserting a tab character.
Focused modifier and editor handlers receive the event first; only an ignored Tab press traverses visible accepting owners, with Shift selecting the reverse direction and other keyboard modifiers leaving the direction unchanged.
Traversal wraps in parent-before-child and declared sibling paint order, while a removed or unmaterialized focused editor loses focus and the next Tab starts from the current first or last eligible owner.

Vertical wheel movement and external ScrollState writes change the current viewport.
Input-driven caret or IME-caret movement follows the caret; `TextWrap.None` additionally pans horizontally, with horizontal position clamped to current content and reset when editor state ownership changes.
Word and Character wrapping keep horizontal position at zero.
TextArea clips exactly its inner viewport after painting its frame, while line and glyph culling retain original sampling coordinates and shadow order.
Current layout is replaced when its value or layout inputs change; no historical text or layout cache is retained.

The semantic role is `SemanticsRole.TextArea`, its committed content is `Semantics.value`, and disabled state is explicit.
The current semantics API does not expose typed accessibility edit or focus actions; role and value reporting alone does not implement those actions.

## Single-line editing

The ordinary `TextField` is 200 by 20 logical pixels.
Its explicit-size overload supports extents of at least 9 by 9, preserving the native one-pixel nine-slice border and centered glyph row.
`TextFieldState` remains caller-owned and confined to the thread that creates it.
Its `maxLength` is a positive number of UTF-16 code units, with a default of 32.
For example, `日` uses one unit and `🙂` uses two; `TextFieldState("日🙂", maxLength = 3)` fits exactly.
Programmatic construction or assignment rejects an oversized value, isolated surrogate, C0 control, DEL, NEL, line separator, paragraph separator, or the section-sign formatting marker.
Invalid assignments leave the previous value unchanged.

Committed input uses the same character policy.
When only one UTF-16 unit remains, a supplementary character is consumed without inserting either surrogate.
The field never truncates a committed Unicode scalar to fit.
Left, Right, Backspace, and Delete move or delete one Unicode scalar at a time; Home and End move to the string boundaries.
Pointer placement and horizontal scrolling use the selected font's measured advances and preserve scalar boundaries.
Caret and composition positions retain signed native widths rather than the non-negative layout extent.
Arithmetic uses bounded integer geometry, omitting caret and underline portions outside the field when unusual font metrics move them beyond its edges.
Editable text stays in logical scalar order, matching the native EditBox default formatter; display text and labels use the font backend's shaping and bidirectional ordering.

Scalar editing is not grapheme-cluster editing.
A combining mark, variation selector, or part of an emoji ZWJ sequence can therefore be moved over or deleted separately.
The built-in editor does not provide selection ranges, clipboard commands, or word-navigation commands.
Existing focused input modifiers can still consume an event before the editor handles it.

## IME composition

A delivered preedit event is displayed inline at the committed cursor, with its supplied UTF-16 caret and focused block.
Preedit text is temporary presentation state and does not enter `TextFieldState.value` until committed character events arrive.
TextArea applies the same separation to `TextAreaState.value` and bounds the complete normalized composed value by its state's maxLength.
Oversized or malformed TextArea preedit is rejected before replacing the previous composition, cursor, layout, or scroll position; repeated replacement does not accumulate history.
Canonical full text controls layout reuse, while caret and focused-range changes update only their necessary presentation phases.
Malformed surrogate boundaries are rejected rather than exposing half a character.
An empty or cleared native preedit event removes the composition; focus loss, detachment, disabling, and external value changes also clear it.
Changing TextArea's viewport, font, style, wrapping, or frame alone preserves the current composition.

Minecraft 26.1 and 26.2 activate Minecraft's existing text-input mode for focused targets whose `FocusTargetNode.requiresTextInput` capability is true.
Enabled `TextField` and `TextArea` components supply this capability; passive input observers keep its default false value.
Native focus acquisition runs after retained operations complete because Minecraft may synchronously resubmit preedit, while focus loss, screen removal, and close release the previous native input ownership.

This preserves Strata's inline composition contract.
It does not reproduce Minecraft's native IME popup, position the operating system's candidate window, or install new platform IME hooks on adapters that expose only committed characters.
Native EditBox pixel comparisons apply to committed text and cursor rendering; inline preedit tests verify event delivery, value isolation, caret position, and focused-block state separately.

## Rendering density

GUI scale affects the physical detail available to each glyph.
Use a larger Minecraft GUI scale or headless output scale when small glyphs lose fine strokes.
Both renderers sample original font resources at the final output density; enlarging an already rendered image cannot recover omitted detail.
The text component previews use scale 2 without changing their logical viewport.

## Compatibility and verification

Glyph coverage and native rendering depend on the selected resources and target contract.
The [font guide](fonts.md) describes offline configuration and numeric boundaries, and [font verification](../development/font-verification.md#acceptance-evidence) defines the evidence needed for native equality claims.
Custom `UiText` visitors must follow the [source compatibility contract](../reference/element-spi.md#source-compatibility).
