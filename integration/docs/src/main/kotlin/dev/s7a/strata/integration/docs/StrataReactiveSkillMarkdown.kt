package dev.s7a.strata.integration.docs

/**
 * Owns generated reactive authoring guidance; fragments preserve their caller's document whitespace.
 */
internal object StrataReactiveSkillMarkdown {
    /**
     * Renders the exact source and binary extension inventory as a fragment for the modifier reference.
     */
    internal fun extensions(
        signatures: KotlinSourceSignatureInventory.Result,
        stateExtensions: Map<String, List<String>>,
    ): String =
        """### StateSource extensions

Import `dev.s7a.strata.state.map` to derive a read-only source. Retain projections outside reevaluation; direct component inputs observe them automatically and suppress equal mapped results. Transformations must be pure and inexpensive. Ordinary subscriptions retain the source's revision and close contracts.

```kotlin
${signatures.stateExtensions.values.flatten().joinToString("\n")}
```

<details><summary>Compiled extension fingerprints</summary>

```text
${stateExtensions.values.flatten().joinToString("\n")}
```

</details>"""

    /**
     * Renders reactive authoring choices and counterexamples around the compiled API-only example.
     */
    internal fun patterns(reactiveExample: String): String =
        """## Choose the smallest reactive boundary

1. Fixed value: pass a literal.
2. Existing `StateSource`: pass it directly to the supported argument.
3. Transformed display: retain `source.map { ... }` outside reevaluated content and pass that projection directly.
4. Structural addition, removal, or switching: use a narrow `Observe` (one through 22 typed sources).
5. Retain editor, selection, and scrolling state outside every observed callback.
6. Large histories: use `VirtualList` with stable keys and retained navigation state.

```kotlin
$reactiveExample
```

The example requires unique history strings as keys; real messages should use their immutable message ID.
Caller-owned sources publish immutable snapshots. No manual screen refresh or close/reopen is needed.
Text, progress, image/head/source descriptors, slot binding/highlighting, labels, enabled/selected flags, cycle label formatting, list items, and leading/trailing availability have direct source overloads. Literal and source arguments may be mixed; consult the exact component signatures.
Editing values and selections still use their dedicated mutable state. Size, color, decoration, and layout arguments use literals or a narrow `Observe`.
For a light or colored editor, use `TextInputAppearance.Custom` with `TextStyle.ContainerLabel`.
Supply normal/focused/disabled nine-slice frames, caret and composition colors, and nonempty image centers after borders.
Custom frames replace the editor frame, including transparent pixels; a background modifier cannot replace it.
Retain images, appearance, and state outside reevaluation; use a narrow Observe for theme changes.
Appearance-only changes repaint without remeasurement or loss of focus/composition/scroll, but may still upload pixels.
Indexed list counts and lookup functions remain one coherent caller-owned indexed API: mutate the backing model and call its existing `refresh()`. Do not split count and lookup into independent sources.
Canvas frame and tiled-image tile delivery retain their dedicated lifetimes; a direct source replaces the source descriptor itself.

## Avoid unnecessary reevaluation

Default Text has natural single-line size.
For a reserved clock/loading rectangle, use `TextLayout.Multiline()`, including a Text inside a fixed-size Observe.
Literal and source-backed text share this geometry contract.

| Pattern to avoid | Actual cost or missing behavior | Replacement |
| --- | --- | --- |
| One `Observe(clock, history, sending)` around the whole screen | Every clock change invokes the screen-sized callback and supplies new child definitions; captured child callbacks also change. | `Text(clock)`, direct list items, and retained enabled/label projections. |
| `Text(snapshot.value)` | This is a literal; publishing a later revision does not update it. | `Text(source)` or a retained `source.map { ... }`. |
| Creating `state.map { ... }` inside an `Observe` | A new source identity causes graph admission and initial transformation; changed parent content still refreshes the child declaration. | Create the projection once beside the retained editor/list state. |
| Creating `TextAreaState` or list navigation state inside reevaluation | Replaces editing/scroll ownership and can reset focus, composition, cursor, or the visible anchor. | Retain each dedicated state outside callbacks. |

`Observe` is one layout child and emits zero or one root; put weight and parent alignment on the region.
Direct inputs keep modifiers on the actual component.
Projections share committed source snapshots in a tree; equal results stop downstream work, although changed inputs may still run the mapper.
Ordinary subscriptions retain every revision, including equal mapped values.
Mappers and declaration callbacks must not mutate sources or perform I/O; publish one immutable model for atomic field changes.
Changed parent callbacks refresh captures even with stable keys, and real text-width changes still require ancestor measurement.

## Rendering cost

Cached foreground callbacks still contribute commands to composition.
A lower-layer change can require rasterizing and uploading an unchanged translucent foreground; geometry changes can also invalidate ancestor overlays.
Strata does not promise per-component damage rectangles or skip fully occluded updates.
Limit full-area translucent layers around frequent updates, or measure their complete composition at the intended resolution and rate.
Use native rasterization/upload counts and final pixels alongside UI counters; see [render monitoring](https://github.com/sya-ri/strata/blob/master/docs/development/render-monitoring.md).
Diagnostics belong in the runtime test harness, outside application UI source."""
}
