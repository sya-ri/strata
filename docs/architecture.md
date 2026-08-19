# Architecture

Strata is a declarative UI framework for Minecraft.
Applications describe a tree of UI nodes and state; runtimes translate that description into platform operations while preserving deterministic layout and input behavior.
This document defines the target `0.1.0` architecture; a module joins the build only with working behavior and tests.

## Module boundaries

- `api` contains the public, platform-neutral contracts and value types.
- `runtime:headless` provides a Minecraft-independent runtime for deterministic layout, input, and rendering tests.
- `runtime:minecraft` contains platform-facing abstractions without importing `net.minecraft` or Fabric types.
- `runtime:minecraft-fabric-26.2` adapts those abstractions to a specific Fabric and Minecraft version.
- `integration:minecraft` contains shared Minecraft integration contracts.
- `integration:minecraft-fabric` contains Fabric integration shared by supported game versions.
- `integration:minecraft-fabric-26.2` contains version-specific Fabric wiring.

Platform-independent code must not depend on a Minecraft runtime.
The headless runtime remains usable in unit tests and CI without a game process, client, or server.
Minecraft and Fabric dependencies are confined to the runtime and integration layers that require them.

The process and compatibility requirements for a new version adapter are defined in [Supporting a new Minecraft version](minecraft-versions.md).

## Testing strategy

The release test suite must exercise `api` and the headless runtime with ordinary JVM tests.
Integration tests belong at the narrowest module boundary that needs them.
Fabric GameTests are reserved for behavior that genuinely requires Minecraft's loaded game environment.
