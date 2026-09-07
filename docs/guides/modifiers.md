# Modifiers

Modifiers add sizing, painting, semantics, and input behavior to a component.
Build a chain from `Modifier.Empty`; the first modifier is outermost and changes the constraints or bounds seen by later modifiers.
Changing the chain preserves the component's keyed identity and logical subtree.

For example, padding on a container insets the whole group once, while padding on every child changes each child's box.
See [layout](../guides/layout.md) and the [component overview](../reference/components.md) for compiled compositions.
The [API reference](https://gh.s7a.dev/strata/) lists exact overloads.

## Size and padding

`size`, `width`, and `height` request exact non-negative extents that are clamped into the parent constraints.
`sizeIn`, `widthIn`, and `heightIn` apply inclusive non-negative ranges independently to each selected axis.
A requested range that does not overlap its parent range is pinned to the nearest parent boundary.
`fillMaxSize`, `fillMaxWidth`, and `fillMaxHeight` fill selected bounded axes while preserving selected unbounded axes.

`padding` reduces child constraints by checked `Insets`, preserves an unbounded maximum, and floors each smaller finite endpoint at zero.
It places the measured child at the left and top inset and reports the child extent plus both inset totals, constrained by the parent.
An extent addition that cannot be represented as an `Int` fails instead of wrapping or saturating.

## Fit a design surface

`scaleToFit(contentSize, contentAlignment, allowUpscaling)` measures its one virtual child exactly once at the strictly positive `contentSize`, reports `constraints.constrain(contentSize)`, and applies one uniform transform to the child's complete effective subtree during layout.
It therefore does not implicitly fill a loose bounded parent.
Put an earlier, outer `fillMaxSize()` before `scaleToFit` when the design surface should use the complete available viewport:

```kotlin
val modifier = Modifier.Empty
    .fillMaxSize()
    .scaleToFit(contentSize = IntSize(320, 180))
```

The first modifier remains outermost, so reversing these calls only fills the child inside the fixed design surface instead of giving `scaleToFit` the complete viewport.
The fit is the smaller width and height ratio, and `contentAlignment` positions the uniformly scaled content in any remaining space without first rounding its offset to an integer.
If either constrained outer axis is zero, the modifier leaves its virtual child unplaced for that pass, so the collapsed subtree contributes no paint, input, or semantics.
The default `allowUpscaling = false` caps the fit at one while still shrinking content that is too large.
This default preserves GUI-scale accessibility: one design unit remains one logical unit whenever the design fits, so the platform GUI density still controls physical size instead of a larger logical viewport silently enlarging the design surface.
Set `allowUpscaling = true` only when the design surface should also grow to consume available space.
Together with outer `fillMaxSize()`, that option makes the fit track viewport growth and shrinkage; on a fixed physical window it compensates for host GUI-density changes and keeps approximately the same physical proportions, subject to the viewport aspect ratio and integer rasterization.
Descriptions after `scaleToFit` are inside its design coordinate space, while earlier descriptions remain in the outer viewport coordinate space.

## Backgrounds and semantics

`background` emits one fill over its complete local bounds before content is painted.
`semantics` emits one separate unresolved entry before content semantics and does not merge descendant values.

Inside a Minecraft screen-content callback, the host-installed profile contributes top-level profile-backed background modifiers without exposing a context object to application code.
`menuBackground()` paints the active menu texture across the modified component's existing bounds and does not change measurement.
`containerBackground(rows)` measures the modified component at the exact generic-container size and paints the selected `generic_54.png` regions before its content.
`imageBackground(image, scale)` paints arbitrary detached resource-pack pixels with typed stretch or clipped tile mapping without changing measurement.
`imageBackground(image, border, centerMode)` applies the same active behavior with Minecraft-compatible horizontal, vertical, or full nine-slice mapping.
These are active modifier nodes rather than standalone components, so they can be composed directly with `Row`, `Column`, `Stack`, or another component and participate in normal modifier identity, update, lifecycle, and failure behavior.

## Activation

`onActivate(action)` composes that primary-pointer press with a focused key-press handler for exactly `KeyCode.Enter` and `KeyCode.Space`, invokes the same typed logical action for either path, and consumes the triggering event without synthesizing a pointer event.
Its keyboard handler makes the logical component a focus candidate, and every delivered `KeyboardEvent.Press` invokes the action, including repeated press deliveries; releases and other keys remain ignored.
Do not add a second simple `onPress` for the same action because consuming handlers stop dispatch, so one registration shadows the other and the result becomes modifier-order-dependent; retain `onPress` when the behavior is intentionally pointer-specific.
`onActivate(enabled, action)` installs the same behavior only when enabled.
A false value returns the exact incoming modifier, retains no action, and adds no pointer, keyboard, or focus-target node, so callers pass the same enabled state to an appearance-only component such as `Button` or `Tab`.

## Pointer input and capture

`onPointerEvent` handles the complete typed pointer protocol and returns an explicit propagation result.
`onCapturedPointerEvent(onCancel, callback)` additionally captures the button whose press that handler consumes, provided no other entry already owns capture.

The tree retains one owner and starting button; moves and matching-button drags or releases go exclusively to that owner even outside its bounds or ancestor clips, use the latest committed local logical coordinates without clamping, and stop propagation even when its callback returns `Ignored`.
Other buttons and scrolling retain ordinary dispatch, and hover remains a real hit-test observation independent of capture.
A matching release clears capture before its callback and never calls cancellation.
Removal, replacement, unplacement, session detach, close, failure, and explicit input reset clear capture before calling `onCancel` once, before that entry's callbacks and resources are disposed.
Updating callback lambdas at the same modifier position preserves capture.
`onPress`, `onRelease`, `onMove`, `onDrag`, and `onScroll` provide typed event-specific handlers; their simple action overloads consume press, release, and scroll while move and drag remain non-consuming.
The simple press overload handles only the primary button, while the typed overload can inspect and decide every button.

`onHover` observes distinct typed enter and exit transitions without consuming movement.
Hover uses half-open accumulated bounds, is recomputed before every pointer move or drag dispatch, and exits during retained session detachment.
Layout movement below a stationary pointer does not create a transition until another move event arrives.

## Keyboard focus and text input

`focusable` adds retained keyboard and text-input focus, while `initialFocus` requests the single unambiguous target selected after layout.
A primary press focuses the deepest and latest-painted accepting target in its laid-out hit path independently of whether pointer behavior consumes the event; focus is retained across ordinary reconciliation and cleared on session detach.
`onKeyEvent`, `onKeyPress`, and `onKeyRelease` receive physical key identity, scan code, and modifier state through the focused component.
`onTextInput`, `onCharacterInput`, and `onPreedit` receive committed Unicode scalar values and immutable input-method composition snapshots through that same owner.
`onFocusChanged` observes distinct gain and loss transitions.
Focused delivery visits modifier nodes from innermost to outermost and then the component node until one returns `Consumed`, allowing active modifiers to override a component's built-in editor behavior.
Only an ignored `KeyboardEvent.Press` for `KeyCode.Tab` starts automatic traversal; Tab release never traverses, Shift reverses direction, and Control, Alt, Super, Caps Lock, and Num Lock do not change direction.
Traversal scans placed logical component owners in parent-before-child and declared sibling paint order, wraps at both ends, and uses the current owner's position as its anchor even when that owner no longer has an accepting target.
With no current focus, forward traversal selects the first eligible owner and reverse traversal selects the last.
An eligible owner has an accepting focus target and a nonempty intersection with the root viewport and every ancestor `ClipChildrenNode` bound; placed VirtualList overscan rows outside the clip are therefore not candidates.
A currently focused owner remains focused while merely hidden by a clip, continues to receive ordinary focused keys such as Enter, and moves to a visible candidate only on explicit traversal.
If reconciliation removes or unplaces the owner, including VirtualList row rematerialization, layout clears focus without remembering a stable key; the next Tab starts from the first or last currently eligible owner.
Custom editors opt into native text-input mode by implementing `FocusTargetNode.requiresTextInput`; its default is false, so keyboard shortcuts and passive input observers do not enable an IME.
Enabled `TextField` and `TextArea` components supply this capability automatically.
The runtime publishes a detached identity for the current editable focus interval, and adapters synchronize native focus after retained transactions finish; loss and screen removal release it before another native screen acquires focus.
Changing a custom target's text-input capability requires presentation invalidation so the next committed frame can reconcile it.

## Parent-scope modifiers

`weight`, child `align`, and content-position modifiers describe the relationship to the parent that consumes them.
They are available only through that parent's layout scope.
They do not establish global settings or affect arbitrary descendants.
When matching providers are composed, the innermost provider for the same key wins.
See [layout](../guides/layout.md) for built-in parent-scope behavior and [tiled images](../guides/tiled-images.md#painting-and-overlays) for content-position overlays.

## Updates and extension

Changing size, padding, or scale-to-fit `contentSize` invalidates measurement, while changing scale-to-fit alignment or upscaling policy invalidates layout.
Changing a background invalidates paint, and changing semantics invalidates only semantics.
Changing a pointer, keyboard, text-input, preedit, or focus callback updates live input behavior without invalidating a frame phase.
An equal value does not invalidate a phase.

Modifiers are active retained nodes.
To implement a new behavior or layout parent-data provider, follow the [Modifier SPI](../reference/modifier-spi.md), which defines identity, lifecycle, threading, and failure handling.
