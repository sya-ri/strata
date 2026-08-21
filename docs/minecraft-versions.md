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
