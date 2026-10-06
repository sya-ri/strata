# Strata authoring checks

The optional `strata-detekt-rules` plugin checks application code that uses Strata.
It is published alongside Strata starting with 0.2.2 and uses the `strata` rule set.
The library's unpublished source-style profile uses `strata-internal`.

## Installation

Apply Detekt to the application's JVM build, enable Maven Central, and add the plugin by its Maven coordinate:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    detektPlugins("dev.s7a.strata:strata-detekt-rules:0.2.2")
}
```

Use the Detekt version selected in Strata's [version catalog](../../gradle/libs.versions.toml).
Compatibility with other Detekt versions must be verified before loading the plugin; the host supplies Detekt's analysis API.
The plugin does not install a Strata runtime in the application.

Merge the following section into the project's `detekt.yml` to enable the rules:

```yaml
strata:
  StateCreatedDuringComposition:
    active: true
  UnusedComponentModifier:
    active: true
  DiscardedModifier:
    active: true
  MultipleModifierApplications:
    active: true
  ParentDataOnWrongParent:
    active: true
  StateMutationDuringComposition:
    active: true
  SubscriptionDuringComposition:
    active: true
  HostAccessDuringComposition:
    active: true
  InvalidRootCount:
    active: true
```

All nine rules require type analysis with `strata-api` and the application's dependencies on the analysis classpath.
Use a JVM source-set task such as `detektMain`; an untyped syntax-only run does not verify these rules.
Point Detekt at that configuration and include the type-aware task in the project's verification gate:

```kotlin
detekt {
    config.setFrom(files("detekt.yml"))
}

tasks.named("check") {
    dependsOn("detektMain")
}
```

Run `./gradlew detektMain` directly during iteration, or `./gradlew check` for the configured gate.
For a different source set or module, select its corresponding type-aware task.
Consult [Detekt's Gradle integration](https://detekt.dev/docs/gettingstarted/gradle) for custom source sets and [custom rule configuration](https://detekt.dev/docs/introduction/extensions) for plugin loading.

## Rules at a glance

| Rule | Problem detected |
| --- | --- |
| `StateCreatedDuringComposition` | Retained state or a source projection is recreated during declaration evaluation. |
| `UnusedComponentModifier` | A component accepts the caller's modifier and never reads it. |
| `DiscardedModifier` | An immutable modifier operation returns a chain that is discarded. |
| `MultipleModifierApplications` | The caller's modifier is forwarded to several component boundaries on compatible paths. |
| `ParentDataOnWrongParent` | Parent data is attached below a layout that does not consume it. |
| `StateMutationDuringComposition` | A declaration changes retained state while evaluating the UI. |
| `SubscriptionDuringComposition` | A declaration acquires a manual subscription during reevaluation. |
| `HostAccessDuringComposition` | A declaration opens a UI or invokes a concrete runtime during evaluation. |
| `InvalidRootCount` | A provable flat definition has no root or multiple roots; an observed region has multiple roots. |

The examples below omit imports for readability.
Their valid and invalid forms are compiled against the public API and checked with the plugin during publication verification.

## StateCreatedDuringComposition

A component declaration can run repeatedly when its inputs change.
Creating editing, scrolling, selection, or navigation state inside that declaration loses the retained state and allocates a replacement on every evaluation.
Creating `mutableStateOf` or a source `map` projection there has the same ownership problem.

Avoid creating the scroll owner while declaring its contents:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.ResettingScroll() {
    ScrollArea(ScrollState()) { Text("Content") }
}
```

Create the state before evaluation, retain it in the screen owner, and pass it into the component:

<!-- checked-example: valid -->

```kotlin
fun UiScope.RetainedScroll(scroll: ScrollState) {
    ScrollArea(scroll) { Text("Content") }
}
```

The rule recognizes standard state constructors and factories through resolved symbols, including aliases.
It follows selected immediate standard-library callbacks, such as collection `forEach`, `map`, and scope functions.
Owner field initializers and deferred events are allowed.
It does not infer arbitrary helper execution or lazy sequence callbacks, and it does not prove that the owner retains a state for the correct lifetime.

## UnusedComponentModifier

A reusable component's `modifier` parameter is the caller's control over its boundary: size, placement, decoration, input, and semantics.
Ignoring that parameter silently removes the caller's requested behavior.

This component accepts a modifier but never uses it:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.IgnoringPanel(modifier: Modifier = Modifier.Empty) {
    Column { Text("Title") }
}
```

Forward it to the outer root:

<!-- checked-example: valid -->

```kotlin
fun UiScope.ForwardingPanel(modifier: Modifier = Modifier.Empty) {
    Column(modifier = modifier) { Text("Title") }
}
```

The rule checks resolved `Modifier` parameters in Strata declaration functions and reports parameters that are never read.
Aliases and shadowed names are resolved.
A read alone does not prove correct placement: using the modifier only on an inner label can still require review.

## DiscardedModifier

Modifier operations return new immutable chains; they do not mutate the receiver.
Discarding the result means the requested operation has no effect.

The padding below never reaches the component:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.UnpaddedPanel(modifier: Modifier = Modifier.Empty) {
    modifier.padding(8)
    Column(modifier = modifier) { Text("Title") }
}
```

Use the returned chain; conditional expressions can supply it too:

<!-- checked-example: valid -->

```kotlin
fun UiScope.ConditionalPanel(enabled: Boolean, modifier: Modifier = Modifier.Empty) {
    val applied = if (enabled) {
        modifier.padding(4)
    } else {
        modifier.padding(8)
    }
    Column(modifier = applied) { Text("Title") }
}
```

The rule reports resolved Modifier operations whose results are unused according to Kotlin's expression analysis.
Returned factory values, assigned or passed chains, used `if` / `when` / `try` branch results, and intermediate results used by another operation are allowed.
Unused branches, nonterminal operations in a used block, `finally` results, and Unit callbacks are still checked, including branches without braces.
Unrelated application methods with the same name are not treated as Modifier operations.

## MultipleModifierApplications

A caller's modifier describes one component boundary.
Forwarding the same chain to both the root and a child can duplicate padding, input handlers, semantics, or parent data.

Avoid applying the caller's modifier to two nodes:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.RepeatedPanel(modifier: Modifier = Modifier.Empty) {
    Column(modifier = modifier) {
        Text("Title", modifier = modifier)
    }
}
```

Use it once on the root and give children independent modifiers:

<!-- checked-example: valid -->

```kotlin
fun UiScope.SingleBoundaryPanel(modifier: Modifier = Modifier.Empty) {
    Column(modifier = modifier) {
        Text("Title", modifier = Modifier.Empty.padding(2))
    }
}
```

The rule follows the parameter and immutable local aliases into Unit-returning `UiScope` declaration calls.
Applications in mutually exclusive `if` or `when` branches are allowed, as are applications belonging to separate `UiDefinition` trees.
It does not prove helper emission, mutable-alias flow, loop iteration counts, or that a single application is on the outer root.

## ParentDataOnWrongParent

Scoped modifiers describe data for the immediate layout parent.
For example, Row and Column consume weight, but Stack does not.
Capturing an outer Row scope can make a misplaced weight compile without making it affect the intended child.

Here the weighted Text is a child of Stack:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.MisplacedWeight() {
    Row {
        val row = this
        Stack {
            Text("Content", modifier = row.run { Modifier.Empty.weight(1f) })
        }
    }
}
```

Attach the weight to Stack, the Row's direct child:

<!-- checked-example: valid -->

```kotlin
fun UiScope.DirectChildWeight() {
    Row {
        Stack(modifier = Modifier.Empty.weight(1f)) {
            Text("Content")
        }
    }
}
```

The rule checks weight, scoped alignment, and tiled-image placement in directly passed modifiers under known built-in parents.
Row and Column both consume weight, while their alignment contracts differ.
Manual Observe is a layout region: apply the Row's weight to Observe itself, rather than to the Text inside it.
The rule does not infer retained modifier aliases, custom forwarding, or explicitly selected outer receivers.

## StateMutationDuringComposition

Declaration evaluation describes the UI for the current state.
Changing that state during evaluation can schedule another update, reset user input, or make the result depend on evaluation order.

Avoid resetting the state as part of the declaration:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.ResettingCounter(state: MutableState<Int>) {
    state.value = 0
    Text("Counter")
}
```

Perform the change in an event or in the owner outside evaluation:

<!-- checked-example: valid -->

```kotlin
fun UiScope.CounterAction(state: MutableState<Int>) {
    Text("Reset", modifier = Modifier.Empty.onPress { state.value = 0 })
}
```

The rule recognizes direct and compound assignments, increments and decrements of known public mutable state properties, and standard selection or navigation mutation calls.
Queries and deferred events are allowed.
Immediate `run` or collection `forEach` does not move a write out of evaluation.
The rule does not trace application-specific publishers or indirect helper writes.

## SubscriptionDuringComposition

A manual subscription owns a handle that must be retained and closed.
Acquiring it during declaration evaluation can add another listener on each reevaluation and leave earlier listeners alive.

Avoid subscribing in the declaration:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.SubscribingLabel(source: StateSource<String>) {
    source.subscribe { }
    Text("Label")
}
```

For a supported component, use its managed source input:

<!-- checked-example: valid -->

```kotlin
fun UiScope.ManagedLabel(source: StateSource<String>) {
    Text(source)
}
```

The rule recognizes `StateSource.subscribe`, including calls through implementing receivers, and standard state observation methods during evaluation.
Managed component bindings and Observe are allowed.
When application-specific manual observation is necessary, the owner acquires the handle outside composition and closes it on replacement or disposal.
The rule does not prove cleanup; internal observation APIs also retain their explicit integration opt-in requirements.

## HostAccessDuringComposition

Opening a UI and operating a concrete runtime belong at integration or event boundaries.
Doing either while describing another UI introduces host effects that may repeat when that UI reevaluates.

Avoid opening another definition during evaluation:

<!-- checked-example: invalid -->

```kotlin
fun UiScope.OpeningLabel(next: UiDefinition) {
    next.open()
    Text("Next")
}
```

Open it from a deferred navigation event:

<!-- checked-example: valid -->

```kotlin
fun UiScope.NavigationLabel(next: UiDefinition) {
    Text("Next", modifier = Modifier.Empty.onPress { next.open() })
}
```

The rule reports resolved `UiDefinition.open` calls and calls or constructors declared in Strata's concrete runtime namespace during evaluation.
Definition construction, deferred events, and composition through the public Element/Node SPI are allowed.
It does not infer arbitrary service calls or determine the application's business responsibility boundaries.

## InvalidRootCount

A UiDefinition needs exactly one root.
Observe permits no root or one root; ordinary layout content permits several children.
Sibling roots must be placed inside an intentional layout so the tree has an unambiguous boundary.

This definition emits two roots:

<!-- checked-example: invalid -->

```kotlin
fun siblingRoots() = UiDefinition {
    Text("One")
    Text("Two")
}
```

Give the definition one layout root:

<!-- checked-example: valid -->

```kotlin
fun groupedRoot() = UiDefinition {
    Column {
        Text("One")
        Text("Two")
    }
}
```

The rule reports empty definitions and excessive roots only when the block consists entirely of known standard component calls.
It leaves blocks containing unknown helpers, declarations, or control flow for review.
A clean report therefore does not prove root cardinality in every possible component tree.

## Scope of the checks

Names are resolved from the analysis classpath, so aliases work and unrelated application methods with the same spelling are not treated as Strata operations.
The plugin uses known API contracts and leaves unproven cases for review rather than imposing application-name or page-discriminator heuristics.

These rules do not automatically judge application responsibility boundaries, meaningful reuse, or whether composition is sufficient for a custom primitive.
Review the [Strata skill](../../skills/strata/SKILL.md) alongside the actual tree and verify state, input, semantics, scroll, and drawing behavior.
Before suppressing a finding, confirm the resolved call and execution boundary; keep any necessary suppression local to the reviewed exception.

Contributors can run `:detekt-rules:check` for rule tests and the bounded performance fixture, and `:verifyPublishedAuthoringChecks` to verify the Maven-published plugin against every example above.
The performance fixture measures a warm, already-compiled rule pass through the shared testkit; it excludes compiler startup and file I/O, and it does not constitute a formal repeated regression comparison or impose an absolute timing threshold.
