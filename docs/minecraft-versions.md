# Supporting a new Minecraft version

This document defines the durable process for adding a Minecraft version to Strata.
A version adapter reproduces the behavior and presentation of its own Vanilla version; it must not normalize observable differences to another version.

## Establish the target

1. Resolve the canonical Java Edition version through the Minecraft evidence tools and record whether it is the latest supported release.
2. Verify Fabric Loader, Fabric API, Fabric Language Kotlin, and Loom versions against their primary repositories.
   Update the Gradle version catalog rather than placing versions in module build scripts.
3. Inspect the Loom-downloaded client, mappings, and assets for UI APIs, sprites, fonts, dimensions, state transitions, and rounding rules that are not covered by the Minecraft evidence tools.
4. Record intentional differences from already supported versions in the compatibility table.
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

1. Add the adapter to the CI matrix and run formatting, static analysis, ABI checks, JVM tests, Kover reports, target-appropriate packaging, GameTests, and visual comparison.
2. Publish all artifacts to an isolated Maven repository and build a clean external consumer using only those publications.
3. Inspect the target distribution jar, nested jars, POM, Gradle metadata, bytecode target, Fabric metadata, dependency bounds, license, and absence of integration classes or local paths.
4. Update the README support table, compatibility document, screenshots, and release notes.
   Every compatibility claim must be backed by a passing test or an explicitly documented limitation.

## Close a minor family

Complete an architecture review after the oldest supported patch release in a Minecraft minor family passes every release gate and before work begins on the next older minor family.
The review is part of version support, not optional follow-up work.

1. Revalidate the implementation direction against the differences observed across the completed family.
   Replace version-name branching and copied adapters with capability-based or shared implementations where the evidence supports them.
2. Review the module graph, published artifact count, source ownership boundaries, and consumer dependency surface.
   Keep a versioned module only when its Minecraft/Fabric compile or distribution boundary requires one, and do not merge modules when doing so would weaken binary compatibility or loaded-game verification.
3. Review build-model fidelity in IDEs, Qodana, Detekt, Dokka, and Gradle, especially when physical source roots are linked into multiple versioned projects.
   Prefer structures that preserve accurate dependencies and avoid compensating for a misleading project model with broad inspection exclusions.
4. Compare compilation, static-analysis, loaded-game, packaging, and publication cost across the family.
   Record avoidable repeated work, retained temporary data, redundant rendering, and cleanup or performance debt before extending the matrix.
5. Classify each runtime, integration module, and shared source boundary as retained, consolidated, replaced, or removed, with evidence for the decision.
   Complete required restructuring and its full acceptance gates before adding the first adapter for the next minor family.

## Minecraft 1.21 family closure

The completed family spans the original 1.21 release through 1.21.11 and passed the family review before 1.20 work began.

- Direction: retained.
  The family proved that mapped native differences belong in compile-time release or release-family bridges, while portable layout, state, components, rendering commands, inventory locators, and screen-session behavior remain version-neutral.
  No runtime version-name branch or reflective compatibility dispatch is required.
- Published runtime projects: retained.
  Each of the twelve versioned runtime projects compiles against one exact Minecraft ABI, owns exact Fabric metadata, produces one exact remapped distribution, and intentionally exports the same Strata adapter classes for a mutually exclusive consumer runtime classpath.
  Merging these projects would either compile against the wrong native ABI or place duplicate incompatible native `Screen` methods in one artifact.
- Integration projects: retained and nonpublished.
  Each exact Fabric API fixture compiles the shared suite and launches both development outputs and the remapped production runtime and integration jars.
  Combining them would remove the exact dependency model or reduce loaded-game verification to an inferred compatibility claim.
- Shared runtime and integration source boundaries: consolidated and retained as source roots, not Gradle projects or publications.
  Complete files move into a shared root only after every consuming compiler and loaded client proves them compatible; incompatible input, skin, resource-name, pixel-channel, blit, and runner generations remain in their narrowest proven release-family root.
- Root build metadata: consolidated.
  One typed target matrix now derives documentation aggregation, loaded-client and remap sequencing, publishable runtime selection, artifact coordinates, Java toolchains, and linked source ownership.
  A verification task compares the matrix with every included versioned project and source-link directory so adding an adapter cannot silently omit a release gate.
- Per-target build scripts: retained.
  They keep the exact Minecraft and Fabric dependencies, Loom distribution model, physical source roots, resource expansion, native run tasks, and artifact verification visible to Gradle, IDEs, and static analysis at the project that owns them.
  Moving these details into a new build-logic module would add another configuration boundary and make the linked-source model less explicit without removing any required runtime or integration project.
- Build cost: retained where it proves a real native boundary and reduced for unrelated work.
  Configuration on demand avoids configuring the 28 Loom runtime and integration projects for a targeted API, core, headless, documentation-helper, or benchmark task; aggregate checks still configure the complete graph intentionally.
- Rendering and retention: accepted.
  The family-close JMH run keeps clean retained frames at effectively zero allocation, identifies the fixed detached-output allocation of a fully dirty 54-leaf scene, and confirms that headless allocation scales with the required fresh pixel image.
  Deterministic session tests and every 1.21 loaded client prove terminal content, tree, binding, immutable-frame, prepared-layer, dynamic-texture, and prepared-frame release; see [Rendering performance](performance.md).

This classification is the baseline for the next older minor family.
Reopen it after that family is complete instead of assuming that the 1.21 boundaries remain optimal for earlier native APIs.

## Minor-family review gate

Complete and verify each supported patch in one Minecraft minor family before starting the next older minor family.
At that boundary, review whether the public API, retained runtime boundary, version-specific adapters, linked source roots, and number of Gradle modules still express real working behavior without duplication or placeholder artifacts.
Repeat the rendering benchmarks and retention tests, inspect loaded-client lifecycle evidence for redundant frames or unreleased temporary data, record the result in the canonical architecture, build, version, and performance documents, and only then select the next older supported patch.
Treat a newly discovered native incompatibility as evidence for a typed compile-time boundary, not as permission for version-string dispatch, reflection, or speculative modules.

## Current compatibility

Exactly one versioned Fabric runtime belongs on a consumer runtime classpath.
The artifacts expose the same Strata-owned package entry points and class names intentionally, while inherited native `Screen` methods remain version-specific, so combining them is unsupported and produces duplicate classes.

| Minecraft | Java | Distribution mapping | Version boundary | Verified compatibility |
| --- | --- | --- | --- | --- |
| 26.2 | 25 | Unobfuscated, no remap | `Minecraft.gui.screen()` and `Minecraft.gui.setScreen` | Latest profile and documentation source; exact native/Fabric/headless pixels for the fixed vanilla scenes, exact Fabric/headless Mod scenes, and synchronized inventory behavior |
| 26.1 | 25 | Unobfuscated, no remap | `Minecraft.screen` and `Minecraft.setScreen` | The complete loaded suite passes; the used UI assets are byte-identical and every fixed-scene ARGB receipt hash matches 26.2 |
| 1.21.11 | 21 | Official Mojang mappings, remapped distribution | Legacy `GuiGraphics` rendering, input callbacks, menu clicks, and `Identifier` names | Development and production-jar loaded-client verification covers the shared legacy adapter without claiming cross-version pixel identity |
| 1.21.10 | 21 | Official Mojang mappings, remapped distribution | Legacy `GuiGraphics` rendering, input callbacks, menu clicks, and `ResourceLocation` names | The same development and production-jar loaded-client suite passes against the version-local resource aliases and version-catalog-selected Fabric API integration fixture |
| 1.21.9 | 21 | Official Mojang mappings, remapped distribution | Legacy `GuiGraphics` rendering, input callbacks, menu clicks, and `ResourceLocation` names | The complete shared legacy adapter passes development and production-jar loaded-client verification against the version-local resource aliases and Fabric API fixture |
| 1.21.8 | 21 | Official Mojang mappings, remapped distribution | Primitive Screen and key-binding callbacks, resource-location player skins, and `ResourceLocation` names | The complete loaded suite passes from development outputs and remapped production jars, including portable rendering, input, server-authoritative inventory round trips, skin/resource loading, and cleanup |
| 1.21.7 | 21 | Official Mojang mappings, remapped distribution | Primitive Screen and key-binding callbacks, resource-location player skins, and `ResourceLocation` names | The same complete loaded suite passes from development outputs and remapped production jars against the exact 1.21.7 client and Fabric API fixture |
| 1.21.6 | 21 | Official Mojang mappings, remapped distribution | Primitive Screen and key-binding callbacks, resource-location player skins, and `ResourceLocation` names | The same complete loaded suite passes from development outputs and remapped production jars against the exact 1.21.6 client and Fabric API fixture |
| 1.21.5 | 21 | Official Mojang mappings, remapped distribution | `RenderType.guiTextured` submission, pose-stack carried-item depth, primitive input, resource-location player skins, and `ResourceLocation` names | The complete loaded suite passes from development outputs and remapped production jars against the exact 1.21.5 client and Fabric API fixture |
| 1.21.4 | 21 | Official Mojang mappings, remapped distribution | Unnamed `DynamicTexture`, `RenderType.guiTextured` submission, pose-stack carried-item depth, primitive input, resource-location player skins, and `ResourceLocation` names | The complete loaded suite passes from development outputs and remapped production jars against the exact 1.21.4 client and Fabric API fixture |
| 1.21.3 | 21 | Official Mojang mappings, remapped distribution | Direct `SkinManager.getOrLoad` player-skin result, unnamed `DynamicTexture`, render-type/pose-stack submission, primitive input, and `ResourceLocation` names | The runner-independent loaded suite passes from development outputs and remapped production jars through a standalone client entrypoint, including integrated-server inventory round trips and terminal resource cleanup |
| 1.21.2 | 21 | Official Mojang mappings, remapped distribution | The same direct-skin, unnamed-texture, render-type/pose-stack, primitive-input, and `ResourceLocation` family as 1.21.3 | The complete standalone loaded suite passes from development outputs and remapped production jars against the exact 1.21.2 client and final official Fabric API fixture |
| 1.21.1 | 21 | Official Mojang mappings, remapped distribution | ABGR `NativeImage` accessors, the pre-render-type `GuiGraphics.blit` signature, fixed tiled nine-slices, code-defined Slot/Tooltip treatments, and no bundle progress sprites | The complete standalone loaded suite passes from development outputs and remapped production jars; profile extraction converts native pixels to ARGB, reproduces the verified legacy Slot and Tooltip drawing constants, and uses the active white boss-bar resource pair for progress |
| 1.21 | 21 | Official Mojang mappings, remapped distribution | The same ABGR native-image, pre-render-type blit, fixed-nine-slice, direct-skin, code-defined Slot/Tooltip, and horizontal-progress capability family as 1.21.1 | The complete standalone loaded suite passes from development outputs and remapped production jars against the original Tricky Trials client and its final official Fabric API fixture |
| 1.20.6 | 21 | Official Mojang mappings, remapped distribution | The 1.21 legacy capability family with constructor-based `ResourceLocation` creation and parsing isolated behind compile-time factories | The complete standalone loaded suite passes from development outputs and remapped production jars, including portable rendering, input, integrated-server inventory round trips, player-skin and resource loading, and terminal cleanup |
| 1.20.5 | 21 | Official Mojang mappings, remapped distribution | The same constructor-based `ResourceLocation`, ABGR native-image, pre-render-type blit, fixed-nine-slice, direct-skin, and standalone-runner capability family as 1.20.6 | The complete standalone loaded suite passes from development outputs and remapped production jars against the exact 1.20.5 client and its final official Fabric API fixture |
| 1.20.4 | 17 | Official Mojang mappings, remapped distribution | Legacy menu/list assets, a code-defined black scrollbar track, `GameProfile` skin resolution, primitive key bindings, constructor-based `ResourceLocation`, and release-local standalone cleanup | The complete standalone loaded suite passes from development outputs and remapped production jars against the exact 1.20.4 client and final official Fabric API fixture; active resource-pack assets remain authoritative where that release exposes them |
| 1.20.3 | 17 | Official Mojang mappings, remapped distribution | The same legacy GUI assets, code-defined scrollbar track, `GameProfile` skin resolution, primitive key bindings, constructor-based `ResourceLocation`, and standalone cleanup family as 1.20.4 | The complete standalone loaded suite passes from development outputs and remapped production jars against the exact 1.20.3 client and its final official Fabric API fixture |
| 1.20.2 | 17 | Official Mojang mappings, remapped distribution | The same legacy capability family as 1.20.3 with a resource-pack-controlled header separator present alongside the footer and code-defined scrollbar track | The complete standalone loaded suite passes from development outputs and remapped production jars against the exact 1.20.2 client and its final official Fabric API fixture |
| 1.20.1 | 17 | Official Mojang mappings, remapped distribution | Pre-GUI-sprite `widgets.png`, `slider.png`, `checkbox.png`, and boss-bar atlases, exact atlas nine-slice borders, code-defined EditBox and scrollbar treatments, Authlib 4 skin loading, and release-local standalone cleanup | The complete standalone loaded suite passes from development outputs and remapped production jars against the exact 1.20.1 client and its final official Fabric API fixture; the profile reads every applicable atlas through the active resource manager and detaches the extracted pixels |

The neutral `runtime/minecraft-fabric-shared`, `runtime/minecraft-fabric-identifier`, `runtime/minecraft-fabric-1.21-legacy`, `runtime/minecraft-fabric-1.21.9-legacy`, `runtime/minecraft-fabric-1.21.8-legacy`, `runtime/minecraft-fabric-1.21.6-legacy`, `runtime/minecraft-fabric-1.21.5-legacy`, `runtime/minecraft-fabric-1.21.3-legacy`, `runtime/minecraft-fabric-unobfuscated`, `integration/minecraft-fabric-1.21-legacy`, `integration/minecraft-fabric-client-gametest`, `integration/minecraft-fabric-1.21.3-legacy`, and the other release-family integration trees are source ownership boundaries, not Gradle modules or fallback profiles.
The 1.21.8 Kotlin input root is shared through 1.20.1, the complete 1.21.8 Java root is shared through 1.21.4, the 1.21.3 Java root isolates the earlier skin future shared by 1.21.3 through 1.20.5, and the 1.21.5/1.21.6 roots isolate native rendering and version-name accessors.
A runtime version links the complete shared root only after its compiler and loaded tests prove every file compatible, links an additional release-family root only when applicable, and keeps unexplained divergence in its versioned project until resolved.
The identifier root is limited to releases whose official mappings expose that native name; releases such as 1.21.10 through 1.20.1 with `ResourceLocation` own compile-time aliases and factories locally while reusing the compatible implementation roots.
Do not use file-tree include filters to select individual version-compatible sources because IDE and static-analysis Gradle models operate at source-root granularity.
