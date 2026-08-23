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

The neutral `runtime/minecraft-fabric-shared`, `runtime/minecraft-fabric-identifier`, `runtime/minecraft-fabric-1.21-legacy`, `runtime/minecraft-fabric-1.21.9-legacy`, `runtime/minecraft-fabric-1.21.8-legacy`, `runtime/minecraft-fabric-1.21.6-legacy`, `runtime/minecraft-fabric-1.21.5-legacy`, `runtime/minecraft-fabric-unobfuscated`, `integration/minecraft-fabric-1.21-legacy`, `integration/minecraft-fabric-1.21.9-legacy`, `integration/minecraft-fabric-1.21.8-legacy`, `integration/minecraft-fabric-1.21.6-legacy`, `integration/minecraft-fabric-1.21.5-legacy`, and `integration/minecraft-fabric-unobfuscated` trees are source ownership boundaries, not Gradle modules or fallback profiles; the 1.21.8 release-family roots are shared by 1.21.8 through 1.21.5, while the 1.21.5/1.21.6 roots isolate native rendering and version-name accessors.
A runtime version links the complete shared root only after its compiler and loaded tests prove every file compatible, links an additional release-family root only when applicable, and keeps unexplained divergence in its versioned project until resolved.
The identifier root is limited to releases whose official mappings expose that native name; releases such as 1.21.10 through 1.21.5 with `ResourceLocation` own compile-time aliases locally while reusing the compatible implementation roots.
Do not use file-tree include filters to select individual version-compatible sources because IDE and static-analysis Gradle models operate at source-root granularity.
