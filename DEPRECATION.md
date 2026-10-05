# Deprecation List

This document lists deprecated public Strata APIs, their replacements, and their removal schedules.
All entries below are scheduled for removal in 1.0.0.
Deprecation warnings are enabled before removal; builds that treat warnings as errors must migrate or explicitly suppress compatibility uses.

## Modifiers

### Modifier.Empty

- **Deprecated in**: 0.3.0
- **Scheduled for removal**: 1.0.0
- **Replacement**: `Modifier`

The `Modifier` companion object implements the empty modifier chain and starts the same extension functions as any other chain.
Use it both to start a chain and to represent no modifier in a conditional.
`Modifier.Empty` remains an alias for this same object until removal.
For mutable accumulators or `fold`, give the empty starter an explicit `Modifier` type, such as `val initial: Modifier = Modifier`, so type inference does not restrict the accumulator to `Modifier.Companion`.

#### Example Migration

```kotlin
// Before
val sized = Modifier.Empty.size(100, 40)
val optional: Modifier = if (enabled) sized else Modifier.Empty

// After
val sized = Modifier.size(100, 40)
val optional: Modifier = if (enabled) sized else Modifier
```

The interface replaces the previous public class in 0.3.0.
Recompile consumers against the new API and keep all Strata artifacts on the same version; the old compiled class calls are not binary compatible.
See the [modifier guide](docs/guides/modifiers.md) and [Modifier SPI](docs/reference/modifier-spi.md).

## Components

### PlayerHead with an integer size

- **Deprecated in**: 0.1.1
- **Scheduled for removal**: 1.0.0
- **Replacement**: `PlayerHead` with `PlayerHeadScale`

The typed scale overload expresses integer skin-texel scaling directly.
For an old size divisible by eight, use `PlayerHeadScale(size / 8)`.
An arbitrary size that is not divisible by eight has no exact equivalent in the typed overload; choose an integer scale when migrating.

#### Example Migration

```kotlin
// Before
PlayerHead(source = source, size = 24)

// After
PlayerHead(source = source, scale = PlayerHeadScale(3))
```

See the [component examples](docs/reference/components.md#playerhead).

## Client UI entry points

### ScreenDefinition

- **Deprecated in**: 0.2.0
- **Scheduled for removal**: 1.0.0
- **Replacement**: `UiDefinition` and its returned `UiSession`

`UiDefinition` uses the common screen and HUD session model.
Retain the session returned by opening the definition for subsequent lifecycle operations.

#### Example Migration

```kotlin
// Before
val definition = ScreenDefinition("Status") { Text("Ready") }
definition.open()

// After
val definition = UiDefinition("Status") { Text("Ready") }
val session = definition.open()
```

### Screens.open

- **Deprecated in**: 0.2.0
- **Scheduled for removal**: 1.0.0
- **Replacement**: `UiDefinition.open()`

Replace the legacy facade and its `ScreenDefinition` argument with an ordinary `UiDefinition` opening call.

#### Example Migration

```kotlin
// Before
Screens.open(ScreenDefinition("Status") { Text("Ready") })

// After
val session = UiDefinition("Status") { Text("Ready") }.open()
```

See [screens and state](docs/guides/screens-and-state.md) for definition and session ownership.

## Paper UI entry points

### PaperScreens.open with a definition

- **Deprecated in**: 0.2.0
- **Scheduled for removal**: 1.0.0
- **Replacement**: `UiDefinition.open(ownerPlugin, player)` from `dev.s7a.strata.paper`

Use the public Paper API to open a common definition and retain its `UiSession`.

#### Example Migration

```kotlin
// Before
PaperScreens.open(ownerPlugin, player, ScreenDefinition("Status") { Text("Ready") })

// After, with dev.s7a.strata.paper.open imported
val session = UiDefinition("Status") { Text("Ready") }.open(ownerPlugin, player)
```

### PaperScreens.open with a definition factory

- **Deprecated in**: 0.2.0
- **Scheduled for removal**: 1.0.0
- **Replacement**: `PaperUi.open(ownerPlugin, player)` from `dev.s7a.strata.paper`

The factory creates a `UiDefinition` inside the player's execution owner.
Continue to create mutable UI state inside this factory, outside the definition's reevaluated content.

#### Example Migration

```kotlin
// Before
PaperScreens.open(ownerPlugin, player) { ScreenDefinition("Status") { Text("Ready") } }

// After
val session = PaperUi.open(ownerPlugin, player) { UiDefinition("Status") { Text("Ready") } }
```

See the [Paper guide](docs/guides/paper.md) for ownership and scheduling.

## Velocity UI entry points

### VelocityScreens.open

- **Deprecated in**: 0.2.0
- **Scheduled for removal**: 1.0.0
- **Replacement**: `VelocityUi.open(ownerPlugin, player)` from `dev.s7a.strata.velocity`

Return a common `UiDefinition` from the factory and retain the asynchronous session result.
Create mutable UI state inside the factory and never block the UI thread on the returned future.

#### Example Migration

```kotlin
// Before
val opened = VelocityScreens.open(ownerPlugin, player) { ScreenDefinition("Status") { Text("Ready") } }

// After
val opened = VelocityUi.open(ownerPlugin, player) { UiDefinition("Status") { Text("Ready") } }
```

See the [platform events reference](docs/reference/platform-events.md) for the public platform APIs.
