# Modifiers

Modifiers add sizing, painting, semantics, and input behavior to a component.
Build a chain from `Modifier.Empty`: the first modifier is outermost and affects the constraints or bounds seen by later ones.
Changing the chain preserves the component's keyed identity and logical subtree.
See the [component examples](../reference/components.md) and [exact API contracts](https://gh.s7a.dev/strata/).

## Size and padding

`size`, `width`, and `height` request exact non-negative extents clamped to the parent constraints.
`sizeIn`, `widthIn`, and `heightIn` request inclusive ranges; non-overlapping ranges pin to the nearest parent boundary.
`fillMaxSize`, `fillMaxWidth`, and `fillMaxHeight` fill bounded axes and preserve unbounded ones.

`padding` insets the child and adds those insets to its reported size within the parent constraints.
Apply it to a container to inset the whole group, or to individual children to change their boxes.
Geometry overflow fails instead of wrapping.

## Fit a design surface

`scaleToFit(contentSize, contentAlignment, allowUpscaling)` lays out a fixed positive design size and uniformly scales its subtree into the constrained outer size.
It does not fill a loose parent by itself.
To use the complete viewport, put `fillMaxSize()` first:

```kotlin
val modifier = Modifier.Empty
    .fillMaxSize()
    .scaleToFit(contentSize = IntSize(320, 180))
```

Reversing these calls fills the child inside the design surface instead.
Earlier modifiers use viewport coordinates; later modifiers and the component use design coordinates.
Alignment positions unused space, and a zero outer axis leaves the subtree unplaced.

The default `allowUpscaling = false` shrinks oversized content but preserves logical size when it fits, leaving GUI density to control physical size.
Enable upscaling when the design should also grow with the viewport.
The [rendering contract](../development/rendering.md) defines transformed drawing and input.

## Backgrounds and semantics

| Modifier | Effect |
| --- | --- |
| `background` | Paint a fill before content. |
| `menuBackground` | Paint the active menu texture without changing size. |
| `containerBackground(rows)` | Use generic-container dimensions and matching background regions. |
| `imageBackground` | Paint resource pixels with stretch, tile, or nine-slice mapping. |
| `semantics` | Add a separate unresolved entry without merging descendants. |

Profile-backed backgrounds use the screen's installed Minecraft profile.
Apply them directly to the component or layout that owns the surface.

## Activation

Use `onActivate(enabled, action)` for primary pointer presses and focused Enter or Space presses, including repeated key presses.
Pass the same enabled value to the control and modifier: a disabled activation adds no action or focus target.
Do not also register a simple `onPress` for the same action, because consuming handlers shadow each other.
Use `onPress` when the behavior is pointer-specific.

## Pointer input and capture

`onPointerEvent` receives typed events and returns an explicit propagation result.
Event-specific helpers include `onPress`, `onRelease`, `onMove`, `onDrag`, and `onScroll`.
Simple press handles the primary button; typed handlers can inspect all buttons.

Use `onCapturedPointerEvent(onCancel, callback)` for gestures that continue outside component bounds.
It captures the button whose press it consumes, then receives moves and matching drags/releases exclusively, even outside ancestor clips.
Other buttons, scrolling, and hover still use ordinary hit testing.
A matching release clears capture; removal, unplacement, detach, close, failure, or input reset cancels it before disposal.
Updating the callback at the same modifier position preserves capture.

`onHover` reports enter/exit without consuming movement.
It updates on pointer moves/drags and exits on detach; layout movement below a stationary pointer waits for another pointer event.
See [UI sessions](../development/ui-sessions.md#frames-and-input) for dispatch ordering and cleanup failures.

## Keyboard focus and text input

`focusable` accepts keyboard/text focus; `initialFocus` requests the single unambiguous target after layout.
A primary press focuses the deepest accepting target in its hit path, preferring later-painted siblings.
Focus survives ordinary reconciliation and clears on detach or owner removal.

Use `onKeyEvent`, `onKeyPress`, and `onKeyRelease` for physical keys; use `onTextInput`, `onCharacterInput`, and `onPreedit` for committed text and composition.
`onFocusChanged` observes focus transitions.
Focused modifiers run from innermost to outermost before the component, so a consuming modifier can override editor behavior.

An ignored Tab press traverses visible accepting owners in parent-before-child and sibling paint order, wrapping at either end.
Shift reverses direction; other modifiers do not.
Clip-hidden owners cannot be new candidates, although an already focused owner stays focused until traversal or removal.
Removing a virtualized row clears its focus without reacquiring by key.

Custom editors implement `FocusTargetNode.requiresTextInput` to request native text-input mode and invalidate presentation when that capability changes.
Standard enabled editors supply it automatically; passive key handlers do not enable an IME.
See [text editing](text.md#ime-composition) for composition support.

## Parent-scope modifiers

`weight`, child `align`, and content-position modifiers describe a direct child's relationship to its consuming parent.
Use them only in that parent's scope; the innermost provider for the same key wins.
See [layout](layout.md) and [tiled-image overlays](tiled-images.md#painting-and-overlays).

## Updates and extension

Size and padding changes invalidate measurement, backgrounds invalidate paint, and semantics changes invalidate semantics.
Callback replacement updates live input without invalidating a frame phase; equal values remain clean.
Follow the [Modifier SPI](../reference/modifier-spi.md) when adding retained behavior or parent data.
