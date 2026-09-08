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
Indexed list counts and lookup functions remain one coherent caller-owned indexed API: mutate the backing model and call its existing `refresh()`. Do not split count and lookup into independent sources.
Canvas frame and tiled-image tile delivery retain their dedicated lifetimes; a direct source replaces the source descriptor itself.

## Avoid unnecessary reevaluation

Default `Text` measures to its natural single-line size. Do not force a larger fixed `size` or filled weight onto it, including a Text that is the only root of a fixed-size Observe. For a reserved clock/loading rectangle, select `TextLayout.Multiline()` so the text can satisfy that rectangle's constraints. Reactive and literal Text share this geometry contract. The independent skill exercise below preserves a first attempt that compiled but failed this runtime constraint, alongside the corrected deterministic fixture.

| Pattern to avoid | Actual cost or missing behavior | Replacement |
| --- | --- | --- |
| One `Observe(clock, history, sending)` around the whole screen | Every clock change invokes the screen-sized callback and supplies new child definitions; captured child callbacks also change. | `Text(clock)`, direct list items, and retained enabled/label projections. |
| `Text(snapshot.value)` | This is a literal; publishing a later revision does not update it. | `Text(source)` or a retained `source.map { ... }`. |
| Creating `state.map { ... }` inside an `Observe` | A new source identity causes graph admission and initial transformation; changed parent content still refreshes the child declaration. | Create the projection once beside the retained editor/list state. |
| Creating `TextAreaState` or list navigation state inside reevaluation | Replaces editing/scroll ownership and can reset focus, composition, cursor, or the visible anchor. | Retain each dedicated state outside callbacks. |

`Observe` is one layout child and emits zero or one root; put `weight` and parent alignment on that region. Direct source components keep their complete modifier on the real component and forward parent layout data through their internal binding.
Mapping is lazy, pure, nullable-safe, and chainable. Creating a projection does not subscribe. A frame shares the original source subscription and its committed snapshot with all projections. Equal mapped values suppress downstream UI evaluation, node updates, and phase work; the mapper itself may run for a changed input. Ordinary `subscribe` still delivers every revision, including equal mapped values.
Do not perform I/O, mutate sources, or create asynchronous work in a mapper or declaration callback. Notifications during evaluation wait for the next frame. Publish one immutable model when several fields must change atomically.
Parent callback identity changes are reevaluation reasons because arbitrary captured values cannot be compared. Stable keys preserve compatible nodes but do not suppress changed callback evaluation. A real text-width change must remeasure affected ancestors; paint-only progress changes do not.
Overlapping foreground commands and clips remain in the final composition even when their callbacks stay cached. A paint-only lower-layer change can reuse overlay callbacks but still require rerasterizing and uploading the shared native layer, including unchanged translucent foregrounds. Child geometry changes conservatively invalidate ancestor paint too, since an overlay may depend on measured child geometry even with fixed outer bounds. Zero foreground evaluation/paint counts do not mean zero composition cost; Strata does not promise per-component damage rectangles or skip fully occluded state updates.
Runtime tests can use `RuntimeUiDiagnosticsOwner.startRenderMonitoring()` on the current screen and compare actual work after `checkpoint()`. Keep diagnostics imports out of application UI source. See [render monitoring](https://github.com/sya-ri/strata/blob/master/docs/development/render-monitoring.md) for bounded snapshots and native frame assertions."""
}
