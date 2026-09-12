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
Shaping, bidirectional ordering, and multiline layout preserve the selected fonts.

Create the state on the host thread and supply a pack containing `example:body`, or pass another font ID.
This [compiled API-only example](../../integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyUnicodeTextScreen.kt) shows both label and editor selection.

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
 * Creates a one-shot screen with caller-owned [state] created on the host thread.
 * The active resource pack must supply [font].
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

## Input appearance

Use `TextInputAppearance.Custom` on standard TextField or TextArea for a light or colored editor.
Supply normal, focused, and disabled nine-slice frames, a caret color, and a composition underline color.
Use `TextStyle.ContainerLabel` for dark glyphs on light frames; font selection remains independent.

Keep immutable images, appearance, and editing state outside reevaluation.
Use a narrow Observe for theme changes: replacing appearance repaints without remeasurement or loss of focus, composition, or scroll, though native upload may still be required.
See the [compiled reactive example](../../skills/strata/references/patterns.md#choose-the-smallest-reactive-boundary).

Each image needs a nonempty center after removing its border; custom centers stretch and transparent pixels reveal the background.
Padding and glyph metrics do not change.
A background modifier cannot replace the editor's own frame.
Omit appearance or select `TextInputAppearance.Default` to keep the profile frame and default decorations.

## Multiline display

Default Text and `TextLayout.SingleLine` reject hard line breaks.
Use `TextLayout.Multiline` to accept them and measure against the parent's available width; CRLF counts as one break.
The original UiText remains the semantic label even when presentation omits content.

| Wrap policy | Behavior |
| --- | --- |
| `TextWrap.None` | Hard breaks only. |
| `TextWrap.Character` | Wrap at Unicode scalar boundaries. |
| `TextWrap.Word` | Prefer breakable whitespace, then fall back to scalars for overlong segments. |

Word wrapping is not a language-specific line-breaking engine.
Nonbreaking spaces are not preferred breaks; whitespace and insertion offsets are preserved.
Lines have a nine-pixel logical height plus non-negative `lineSpacing` between them.
`maxLines` limits visible lines; `TextOverflow.Clip` clips to the viewport, and `Ellipsis` adds `...` only when it fits.
Natural text preserves glyph overhang; constrained text clips at its actual viewport, with no lines visible at zero height.

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
 * Creates a one-shot screen with independent message and notes editors.
 * Supply distinct caller-owned states created on the host thread and a pack containing [font].
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
Pointer placement follows the current scrolled layout and preserves the selected side of a soft-wrap boundary.
Tab remains focus navigation rather than inserting a tab character.
Focused handlers may consume Tab before [automatic traversal](modifiers.md#keyboard-focus-and-text-input).

Vertical wheel movement and external ScrollState writes change the current viewport.
Input-driven caret or IME-caret movement follows the caret; `TextWrap.None` additionally pans horizontally, with horizontal position clamped to current content and reset when editor state ownership changes.
Word and Character wrapping keep horizontal position at zero.
TextArea clips content to its inner viewport after painting its frame.

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
Editable text stays in logical scalar order, matching the native EditBox default formatter; display text and labels use the font backend's shaping and bidirectional ordering.

Scalar editing is not grapheme-cluster editing.
A combining mark, variation selector, or part of an emoji ZWJ sequence can therefore be moved over or deleted separately.
The built-in editor does not provide selection ranges, clipboard commands, or word-navigation commands.
Existing focused input modifiers can still consume an event before the editor handles it.

## IME composition

A delivered preedit event is displayed inline at the committed cursor, with its supplied UTF-16 caret and focused block.
Preedit text is temporary presentation state and does not enter `TextFieldState.value` until committed character events arrive.
TextArea applies the same separation to `TextAreaState.value` and bounds the complete normalized composed value by its state's maxLength.
Invalid or oversized TextArea preedit leaves the previous composition, cursor, layout, and scroll unchanged.
Malformed surrogate boundaries are rejected.
An empty or cleared native preedit event removes the composition; focus loss, detachment, disabling, and external value changes also clear it.
Changing TextArea's viewport, font, style, wrapping, or frame alone preserves the current composition.

Minecraft 26.1 and 26.2 activate Minecraft's existing text-input mode for focused targets whose `FocusTargetNode.requiresTextInput` capability is true.
Enabled `TextField` and `TextArea` components supply this capability; passive input observers keep its default false value.
It does not reproduce Minecraft's native IME popup, position the operating system's candidate window, or install new platform IME hooks on adapters that expose only committed characters.
For implementation and verification boundaries, see [UI sessions](../development/ui-sessions.md) and [manual OS IME verification](../development/build.md#manual-os-ime-verification).

## Rendering density

GUI scale affects the physical detail available to each glyph.
Use a larger Minecraft GUI scale or headless output scale when small glyphs lose fine strokes.
Both renderers sample original font resources at the final output density; enlarging an already rendered image cannot recover omitted detail.
The text component previews use scale 2 without changing their logical viewport.

## Compatibility and verification

Glyph coverage and native rendering depend on the selected resources and target contract.
The [font guide](fonts.md) describes offline configuration and numeric boundaries, and [font verification](../development/font-verification.md#acceptance-evidence) defines the evidence needed for native equality claims.
Custom `UiText` visitors must follow the [source compatibility contract](../reference/element-spi.md#source-compatibility).
