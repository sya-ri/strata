# Strata authoring checks

The source-built `quality:consumer-detekt-rules` module provides opt-in Detekt rules for applications using Strata.
It is separate from Strata's internal source-style profile and is not a published Maven artifact.
Build its JAR with `./gradlew :quality:consumer-detekt-rules:build` and use the Detekt version selected by this checkout's version catalog.
Compatibility with other Detekt versions must be verified before loading the plugin.

Add the resulting JAR to the consuming project's `detektPlugins` configuration:

```kotlin
dependencies {
    detektPlugins(files("path/to/strata-consumer-detekt-rules.jar"))
}
```

Enable the rules in the project's Detekt configuration:

```yaml
strata-consumer:
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

All nine rules require type analysis with the selected `strata-api` and the application's dependencies on the analysis classpath.
Use the JVM source-set task, such as `detektMain`, rather than treating an untyped syntax-only run as coverage.
Consult [Detekt's Gradle integration](https://detekt.dev/docs/gettingstarted/gradle) for custom source sets and [custom rule configuration](https://detekt.dev/docs/introduction/extensions) for plugin loading.

| Rule | Skill contract and report | Deliberate limits |
| --- | --- | --- |
| `StateCreatedDuringComposition` | Retain editing, scrolling, selection, navigation state, `mutableStateOf`, and source `map` projections outside reevaluation. Reports resolved standard constructors and factories. | Follows declaration receivers and selected immediate standard-library lambdas, including collection `forEach`, `map`, and scope functions. Does not infer arbitrary helper execution or lazy sequence callbacks. Owner fields and deferred events are valid. |
| `UnusedComponentModifier` | Preserve the caller's modifier. Reports a resolved `Modifier` parameter never read by a Strata declaration function. | A read alone does not establish correct root placement. Aliases and shadowed names are resolved. |
| `DiscardedModifier` | Use immutable operation results. Reports a Modifier operation whose result is a discarded block statement. | Checks resolved Modifier receivers and return types. Returned factory values, assigned/passed chains, and intermediate results used by another operation are valid. |
| `MultipleModifierApplications` | Apply a caller modifier at one component boundary. Reports forwarding the parameter or immutable local aliases to more than one Unit-returning `UiScope` declaration call on compatible syntactic paths. | Allows distinct `if`/`when` branches and separate `UiDefinition` trees. Does not prove arbitrary helper emission, mutable-alias flow, loop iteration counts, or that a single application is the outer root. |
| `ParentDataOnWrongParent` | Put scoped parent data on a direct child of its consuming layout. Reports resolved weight, alignment, or tiled-image placement in a directly passed modifier under a known incompatible built-in parent. | Does not infer retained modifier aliases, custom forwarding, or explicitly selected outer receivers. Row and Column both consume weight; their alignment contracts differ. Manual Observe is a layout region, so its parent data belongs on the region itself. |
| `StateMutationDuringComposition` | Change retained state in events or owner updates. Reports direct/compound assignments, increments/decrements of known public mutable state properties, and standard navigation/selection mutation calls during evaluation. | Does not infer arbitrary application publishers or indirect writes through helpers. Queries and deferred events are valid. |
| `SubscriptionDuringComposition` | Own and close manual subscriptions outside reevaluation. Reports resolved `StateSource.subscribe`, including implementing receivers, and standard state observation methods during evaluation. | Managed component bindings and Observe are valid. Does not establish that an owner actually closes its handles. Internal observation APIs still require their explicit integration opt-in. |
| `HostAccessDuringComposition` | Keep opening and concrete runtimes at integration/event boundaries. Reports resolved `UiDefinition.open` and calls declared in Strata's concrete runtime namespace during evaluation. | Does not infer arbitrary service calls or business responsibilities. Deferred events, definition construction, and public Element/Node composition remain valid. |
| `InvalidRootCount` | Emit one definition root and at most one observed root. Reports an empty definition or multiple flat roots in a block consisting solely of known standard component calls. | Allows empty Observe and multiple layout children. Unknown helpers, declarations, and control flow are not counted; a clean report does not prove root cardinality. |

## Modifier results and component boundaries

Discarding a chain has no effect:

```kotlin
fun UiScope.Panel(modifier: Modifier = Modifier.Empty) {
    modifier.padding(8) // DiscardedModifier
    Column(modifier = modifier) {
        Text("Title", modifier = modifier) // MultipleModifierApplications
    }
}
```

Use the resulting chain on one outer root and give children their own modifiers:

```kotlin
fun UiScope.Panel(modifier: Modifier = Modifier.Empty) {
    Column(modifier = modifier.padding(8)) {
        Text("Title", modifier = Modifier.Empty.padding(2))
    }
}
```

An alternative root for loading or error is valid:

```kotlin
fun UiScope.Status(ready: Boolean, modifier: Modifier = Modifier.Empty) {
    if (ready) Text("Ready", modifier = modifier)
    else Text("Loading", modifier = modifier)
}
```

`UnusedComponentModifier` catches a parameter omitted entirely; `MultipleModifierApplications` catches reuse on compatible paths.
Neither proves correct visual ordering or meaningful component responsibility.

## Parent data follows the actual layout child

Capturing an outer scope makes this compile, but Stack does not consume row weight:

```kotlin
Row {
    val row = this
    Stack {
        Text("Misplaced", modifier = row.run { Modifier.Empty.weight(1f) })
    }
}
```

Apply weight to Stack, the Row's direct child:

```kotlin
Row {
    Stack(modifier = Modifier.Empty.weight(1f)) {
        Text("Content")
    }
}
```

For manual Observe inside Row, apply weight to Observe rather than its inner Text.
Direct supported source-bound Text inputs preserve their standard component parent-data contract.

## State and subscription lifetime

These statements run again when content is reevaluated:

```kotlin
fun UiScope.Editor(source: StateSource<String>, state: MutableState<Int>) {
    val scroll = ScrollState() // StateCreatedDuringComposition
    state.value = 0 // StateMutationDuringComposition
    source.subscribe { } // SubscriptionDuringComposition
}
```

Retain UI state in the owner, use a managed source input, and change state from an event:

```kotlin
fun UiScope.Editor(source: StateSource<String>, state: MutableState<Int>, scroll: ScrollState) {
    ScrollArea(scroll) {
        Text(source, modifier = Modifier.Empty.onPress { state.value += 1 })
    }
}
```

The owner creates `scroll` and `state` before declaration evaluation.
For application-specific manual observation, the owner also retains and closes the returned handle; acquisition outside composition alone does not prove cleanup.
Immediate `run` or collection `forEach` does not move a write or allocation out of evaluation.
A stored callback or a deferred action is a separate boundary; helper execution requires review.

## Roots and host opening

`UiDefinition { Text("One"); Text("Two") }` emits two roots; wrap those children in an intentional `Column`, `Row`, `Grid`, or `Stack`.
An empty UiDefinition is invalid, while empty Observe content is supported.
Ordinary layout content may contain several children.

Open a definition from an integration boundary or a deferred navigation event.
Calling `next.open()` directly in a declaration function is reported; `Modifier.Empty.onPress { next.open() }` is valid.
Creating a future UiDefinition does not open it, and its modifier applications belong to a separate tree.

## Scope of the checks

Names are resolved from the analysis classpath, so aliases work and unrelated application methods with the same spelling are not treated as Strata operations.
The plugin uses known API contracts and deliberately leaves unproven cases for review rather than imposing application-name or page-discriminator heuristics.

These rules do not automatically judge application responsibility boundaries, meaningful reuse, or whether composition is sufficient for a custom primitive.
Review the [Strata skill](../../skills/strata/SKILL.md) alongside the actual tree and verify state, input, semantics, scroll, and drawing behavior.
Do not suppress an entire rule merely to permit one valid construction; first confirm the resolved call and execution boundary.
