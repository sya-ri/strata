# Supporting a new Minecraft version

This document defines the durable process for adding a Minecraft version to Strata.
A version adapter reproduces the behavior and presentation of its own Vanilla version; it must not normalize observable differences to another version.
Minecraft 1.20 is the supported release floor; do not add Minecraft 1.19 or older adapters unless the project scope is explicitly changed.

## Establish the target

1. Resolve the canonical Java Edition version through the Minecraft evidence tools and record whether it is the latest supported release.
2. Verify Fabric Loader, Fabric API, Fabric Language Kotlin, and Loom versions against their primary repositories.
   Update the Gradle version catalog rather than placing versions in module build scripts.
3. Inspect the Loom-downloaded client, mappings, and assets for UI APIs, sprites, fonts, dimensions, state transitions, and rounding rules that are not covered by the Minecraft evidence tools.
4. Record intentional differences from already supported versions in the native adapter boundaries below.
   Treat an unexplained difference as a defect.

## Implement the adapter

1. Add `runtime/minecraft-fabric-{version}` and `integration/minecraft-fabric-{version}` only when both modules contain working implementation or verification code.
2. Keep mapped Minecraft and Fabric types inside the versioned runtime.
   Put stable host, resource, text, and lifecycle contracts in `runtime/minecraft` when they can be expressed without version-specific types or behavior.
   A second adapter is evidence for an abstraction, not a prerequisite for it.
3. Implement version-specific resource resolution, text conversion, screen lifecycle, rendering, input, and environment defaults through the existing public capabilities.
   Do not add component-type dispatch or a parallel component registry.
4. Express Vanilla asset, color, geometry, and behavior differences through the version profile or replaceable variants.
   Never inherit the latest profile as an undocumented fallback.
5. Package the target's distribution artifact with the common Strata artifacts nested exactly once.
   Remap only versions whose mappings and distribution format require it; verify an unobfuscated no-remap artifact directly when that is the target contract.
   Keep integration fixtures and test code out of the mod artifact.

## Verify behavior

1. Run the platform-independent API and headless suites against the adapter's portable contracts.
2. Add Fabric GameTests for text resolution, resources, layout, input, lifecycle, threading, and the Vanilla screens used to validate the adapter.
3. Capture screenshots at a fixed viewport, GUI scale, locale, resource pack, focus, pointer state, and game state.
   Store expected, actual, and diff output on visual failure; verification tasks must never rewrite goldens.
4. For the latest supported version, provide the complete asset-backed headless profile and require tree, measurement, logical draw-command, and exact ARGB image parity with the game capture for the supported scene and fixed environment.
5. When the new adapter becomes the latest version, move the canonical asset-backed headless and README examples to it.
   Older adapters keep their version-specific GameTests and structural headless tests; only complete asset-dependent headless pixel parity may be relaxed.

## Release the version

1. Confirm that the paired runtime and integration project directories are discovered by the generated CI matrix, then run formatting, static analysis, ABI checks, JVM tests, Kover reports, target-appropriate packaging, GameTests, and visual comparison.
   Do not add a version-specific workflow entry; the planner must derive its bounded shards from the project inventory.
2. Publish all artifacts to an isolated Maven repository and build a clean external consumer using only those publications.
3. Inspect the target distribution jar, nested jars, POM, Gradle metadata, bytecode target, Fabric metadata, dependency bounds, license, and absence of integration classes or local paths.
4. Update the typed target matrix and regenerate the [runtime compatibility reference](../reference/compatibility.md), affected screenshots, and release documentation.
   Every compatibility claim must be backed by a passing test or an explicitly documented limitation.

## Close a minor family

Complete an architecture review after the oldest supported patch release in a Minecraft minor family passes every release gate and before the supported minor-family range changes again.
The review is part of version support, not optional follow-up work.

1. Revalidate the implementation direction against the differences observed across the completed family.
   Replace version-name branching and copied adapters with capability-based or shared implementations where the evidence supports them.
2. Review the module graph, published artifact count, source ownership boundaries, and consumer dependency surface.
   Keep a versioned module only when its Minecraft/Fabric compile or distribution boundary requires one, and do not merge modules when doing so would weaken binary compatibility or loaded-game verification.
3. Review build-model fidelity in IDEs, Qodana, Detekt, Dokka, and Gradle, especially when physical source roots are linked into multiple versioned projects.
   Prefer structures that preserve accurate dependencies and avoid compensating for a misleading project model with broad inspection exclusions.
4. Compare compilation, static-analysis, loaded-game, packaging, and publication cost across the family.
   Resolve avoidable repeated work, retained temporary data, redundant rendering, and cleanup or performance debt before changing the matrix.
5. Classify each runtime, integration module, and shared source boundary as retained, consolidated, replaced, or removed, with evidence for the decision.
   Complete required restructuring and its full acceptance gates before adding another minor family.

Complete every supported patch in a minor family before changing that family range.
Repeat benchmarks and retention tests, inspect loaded-client lifecycle evidence, and audit runtime and build caches against their ownership and acceptance contract.
Promote durable decisions into architecture, build, rendering, and performance documents instead of appending a completed-family status report here.

## Native adapter boundaries

Consumer artifacts and Java requirements are generated in the [compatibility reference](../reference/compatibility.md).
The following distinctions describe implementation ownership, not cross-version pixel equivalence.

| Minecraft | Native boundary |
| --- | --- |
| 26.3 | SDL physical input, `Window.onFocus(boolean)`, and RenderPearl GPU interfaces and compiled pipelines, with the GUI holder screen API and integer Unihex advance |
| 26.2 | `Minecraft.gui.screen()` and `Minecraft.gui.setScreen` |
| 26.1 | `Minecraft.screen` and `Minecraft.setScreen` |
| 1.21.11 | Legacy `GuiGraphics` rendering, input callbacks, menu clicks, and `Identifier` names |
| 1.21.10 | Legacy `GuiGraphics` rendering, input callbacks, menu clicks, and `ResourceLocation` names |
| 1.21.9 | Legacy `GuiGraphics` rendering, input callbacks, menu clicks, and `ResourceLocation` names |
| 1.21.8 | Primitive Screen and key-binding callbacks, resource-location player skins, and `ResourceLocation` names |
| 1.21.7 | Primitive Screen and key-binding callbacks, resource-location player skins, and `ResourceLocation` names |
| 1.21.6 | Primitive Screen and key-binding callbacks, resource-location player skins, and `ResourceLocation` names |
| 1.21.5 | `RenderType.guiTextured` submission, pose-stack carried-item depth, primitive input, resource-location player skins, and `ResourceLocation` names |
| 1.21.4 | Unnamed `DynamicTexture`, `RenderType.guiTextured` submission, pose-stack carried-item depth, primitive input, resource-location player skins, and `ResourceLocation` names |
| 1.21.3 | Direct `SkinManager.getOrLoad` player-skin result, unnamed `DynamicTexture`, render-type/pose-stack submission, primitive input, and `ResourceLocation` names |
| 1.21.2 | The same direct-skin, unnamed-texture, render-type/pose-stack, primitive-input, and `ResourceLocation` family as 1.21.3 |
| 1.21.1 | ABGR `NativeImage` accessors, the pre-render-type `GuiGraphics.blit` signature, fixed tiled nine-slices, code-defined Slot/Tooltip treatments, and no bundle progress sprites |
| 1.21 | The same ABGR native-image, pre-render-type blit, fixed-nine-slice, direct-skin, code-defined Slot/Tooltip, and horizontal-progress capability family as 1.21.1 |
| 1.20.6 | The 1.21 legacy capability family with constructor-based `ResourceLocation` creation and parsing isolated behind compile-time factories |
| 1.20.5 | The same constructor-based `ResourceLocation`, ABGR native-image, pre-render-type blit, fixed-nine-slice, direct-skin, and standalone-runner capability family as 1.20.6 |
| 1.20.4 | Legacy menu/list assets, a code-defined black scrollbar track, `GameProfile` skin resolution, primitive key bindings, constructor-based `ResourceLocation`, and release-local standalone cleanup |
| 1.20.3 | The same legacy GUI assets, code-defined scrollbar track, `GameProfile` skin resolution, primitive key bindings, constructor-based `ResourceLocation`, and standalone cleanup family as 1.20.4 |
| 1.20.2 | The same legacy capability family as 1.20.3 with a resource-pack-controlled header separator present alongside the footer and code-defined scrollbar track |
| 1.20.1 | Pre-GUI-sprite `widgets.png`, `slider.png`, `checkbox.png`, and boss-bar atlases, exact atlas nine-slice borders, code-defined EditBox and scrollbar treatments, Authlib 4 skin loading, and release-local standalone cleanup |
| 1.20 | The same pre-GUI-sprite atlases, Authlib 4 skin loading, and standalone cleanup capability family as 1.20.1, compiled as an exact version boundary |

## Shared source ownership

Versioned `runtime/minecraft-fabric-<version>` and `integration/minecraft-fabric-<version>` directories own Gradle projects, distributions, and exact-target verification.
Shared runtime sources live under `runtime/shared/minecraft-fabric/`; matching verification sources live under `integration/shared/minecraft-fabric/`.
Game-independent font comparison sources live under `integration/shared/font-parity/`.
These shared directories are source roots, not Gradle projects or published libraries.
The [generated shared-source matrix](minecraft-shared-sources.md) shows the runtime roots compiled by each target.

Group shared sources by responsibility: lifecycle, resources, input, screen, HUD, rendering, Canvas, and transport.
Verification adds runner, scenarios, and font-parity responsibilities.
Within a responsibility, name variants after the API contract they implement, such as primitive callbacks, event callbacks, GUI graphics, GUI extraction, render types, render pipelines, texture views, samplers, and bind groups.
Use `common` only below its owning responsibility.
Avoid relative names such as legacy or modern and abbreviated version labels.
If an API name cannot distinguish a verified variant, append the complete Minecraft version as a disambiguator, not as a compatibility-range claim.

A source root is shared only where the compiler and loaded tests establish compatibility.
Each consumer references complete language roots; do not select compatible files with file-tree include filters, because IDE and static-analysis models operate at source-root granularity.
Keep package names, resource identities, and native class names independent of physical source ownership.
Source sets, static analysis, source links, resource packaging, and verification fixtures must follow every source move together.
Exact target membership remains explicit in the build; no runtime version-string dispatch or directory-name inference selects behavior.

## Integration runner boundaries

The nonpublished `integration:minecraft-fabric-1.21.11` through `integration:minecraft-fabric-1.20` modules compile the complete shared GUI-graphics loaded-client suite and the matching input-generation and version-name roots against their remapped adapters, required Java toolchains, and exact Fabric API dependencies.
Minecraft 1.21.4 and later use the Fabric Client GameTest adapter source root, while 1.21.3 through 1.20 use a standalone client entrypoint because their exact official Fabric API fixtures predate that module; 1.20.4 through 1.20.2 share the runner bridge needed for their dirt-message and level-cleanup APIs, while 1.20 and 1.20.1 share the compiler-proven preceding readiness and level-clear variant in exact owning projects.
Fabric API is confined to these integration modules; the published runtimes do not depend on it.
The generated Minecraft CI plan invokes `ciMinecraftCheck` for every discovered version pair and writes version-qualified build evidence after exercising each remapped adapter inside its actual client.
Those checks package and remap every applicable integration test Mod and runtime Mod, then repeat the loaded suite from the production jars with their nested common runtime jars.
