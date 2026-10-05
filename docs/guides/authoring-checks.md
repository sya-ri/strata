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
```

Both rules require type analysis with the selected `strata-api` and the application's dependencies on the analysis classpath.
Use the JVM source-set task, such as `detektMain`, rather than treating an untyped syntax-only run as coverage.
Consult [Detekt's Gradle integration](https://detekt.dev/docs/gettingstarted/gradle) for custom source sets and [custom rule configuration](https://detekt.dev/docs/introduction/extensions) for plugin loading.

| Rule | Reports | Deliberate limits |
| --- | --- | --- |
| `StateCreatedDuringComposition` | Resolved Strata scroll, editing, virtual-list, pan/zoom, checkbox, cycle, slider, and selection state constructors, `mutableStateOf`, or source `map` factories directly inside a Strata declaration receiver. | Does not infer execution through arbitrary helpers or ordinary callback lambdas; retained owner fields and deferred action callbacks are valid. |
| `UnusedComponentModifier` | A resolved Strata `Modifier` parameter that is never read by a Strata composition function. | A read does not prove the modifier reaches the root once; aliases and shadowed names are resolved, but root placement needs tree review. |

These rules do not automatically judge application responsibility boundaries, meaningful reuse, or whether composition is sufficient for a custom primitive.
Review the [Strata skill](../../skills/strata/SKILL.md) alongside the actual tree and verify state, input, semantics, scroll, and drawing behavior.
Do not suppress an entire rule merely to permit one valid construction; first confirm the resolved call and execution boundary.
