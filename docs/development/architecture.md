# Architecture

Strata separates application declarations from retained UI behavior and platform presentation.
This document defines module responsibilities and extension boundaries for contributors.
For application use, start with [screens and state](../guides/screens-and-state.md).

The API and retained core share their implementation between JVM and JavaScript; platform-specific synchronization, exact arithmetic, identity, and coroutine-context adapters preserve the same observable contracts.
Headless and Minecraft integration remain JVM adapters.

## Module boundaries

| Module | Responsibility | Dependency boundary |
| --- | --- | --- |
| `api` | Screen definitions, components and state, resource identifiers, modifiers, and the public Element/Node SPI. | Platform-neutral; sufficient for application compilation. |
| `runtime:core` | Reconciliation, retained phases, input, semantics, and internal session orchestration. | Depends on `api`; contains no Minecraft integration. |
| `runtime:headless` | Portable command rasterization, immutable frames, and deterministic PNG output. | Uses core contracts without a desktop graphics dependency. |
| `runtime:minecraft` | Profile-backed component implementation, resources, bindings, and screen hosts. | Depends on public contracts and core without mapped game types. |
| `runtime:minecraft-fonts-lwjgl` | Optional CPU font decoding, rasterization, and text ordering. | Uses common font contracts and target-matched native libraries. |
| `runtime:minecraft-fabric-<version>` | Native screen, resource, input, and presentation adapters for one exact target. | Owns mapped Minecraft and Fabric dependencies. |
| `integration:*` | External API, loaded-client, and documentation verification. | Not published. |

Keep this module graph acyclic and include a module only when it contains working, tested behavior.
Application source does not need a runtime module on its compile classpath.
Platform integration belongs at runtime and integration boundaries, and the public SPI never dispatches on a concrete component class.

Each versioned Fabric artifact owns its metadata, dependencies, native ABI, distribution, publication, and verification.
Compatible complete source roots can be shared without becoming Gradle projects or published artifacts.
The typed Minecraft target matrix records build ownership; runtime behavior uses compiled capabilities rather than reading version strings from that matrix.
The [compatibility reference](../reference/compatibility.md) lists consumer choices, while [adapter development](../development/minecraft-versions.md) records native source boundaries.

## Declaration and retained behavior

Applications build immutable descriptions and retain caller-owned state.
A one-shot `ScreenDefinition` transfers its callback to a host that installs the profile and evaluates exactly one root on its owner thread.
The common host exposes lifecycle, frames, and typed input through an opt-in runtime bridge; it does not expose internal session state, coroutine facilities, or mapped Minecraft objects to application source.

The engine reconciles descriptions into retained capability-bearing nodes.
Measurement, layout, paint, and semantics have independent invalidation contracts, and input uses the committed tree and current retained geometry.
Modifiers are active nodes in an effective pipeline ancestry; they preserve the component's logical identity and subtree.
Parent data is exposed only through the layout scope that consumes it.

The [Element SPI](../reference/element-spi.md) and [Modifier SPI](../reference/modifier-spi.md) own those public contracts.
[UI sessions](../development/ui-sessions.md) owns shared lifecycle, state cutoff, coroutine generations, and failure handling.
[Rendering](rendering.md) owns command interpretation and platform presentation; [performance](../development/performance.md) owns cache admission and retention.

## Component extension policy

Strata standardizes only focused primitives that serve at least two natural independent uses and do not encode one screen or application-domain model.
Every proposal for a standard built-in is reviewed for excessive specialization and for whether an ordinary composition of existing primitives is sufficient.
Minecraft-specific primitives remain eligible when their responsibility is broadly reusable across Minecraft UI; a player-head renderer can serve social lists, player lists, profiles, teams, and ownership displays, while a social-entry row or an advancement graph remains application composition.

This standard-library gate does not constrain downstream code.
An application or Mod may define a purpose-specific component such as an energy gauge or social entry as an ordinary `UiScope` composition function, or implement new retained behavior with a custom immutable `Element`, stable singleton `ElementType`, and capability-bearing `Node`.
`UiScope.element` inserts that description without registration, and the retained core never dispatches on the concrete component class.
External component implementations receive the same reconciliation, key, modifier, cache, input, semantics, lifecycle, and failure contracts as Strata's built-ins.
The complete external implementation contract is documented in [Element SPI](../reference/element-spi.md).

Keep domain state, loading, and workflows in the consuming application.
Use a retained primitive only when existing composition cannot supply the required UI responsibility.

## Resources and ownership

Public resource identifiers and immutable pixels may cross common application boundaries; only a versioned client resolves mapped native resources.
Profiles retain detached immutable data, while mutable caches, native faces, subscriptions, and presentation textures have explicit host, attachment, or device owners.
Transient screen detachment preserves the common retained tree but suspends attachment-bound observations and releases platform presentation resources as their contracts require.
Terminal cleanup releases every owned resource and preserves the primary failure when cleanup also throws.

[Font resources](../guides/fonts.md), [Canvas](../guides/canvas.md), and [tiled images](../guides/tiled-images.md) describe the public integration contracts.
Their internal cache and native presentation rules belong in the shared development documents rather than in this overview.

## Verification boundaries

Use ordinary JVM tests wherever behavior does not require a loaded game.
`integration:api` mechanically checks API-only application compilation and exercises external primitives through the public contracts.
Loaded-client tests prove native resources, rendering, input, server-authoritative bindings, packaging, and lifecycle at the exact target boundary.
Independent font comparison is required where sharing a portable renderer would otherwise hide a native mismatch.

[Build and verification](../development/build.md), [font verification](font-verification.md), and [documentation maintenance](documentation.md) define the corresponding commands and evidence ownership.
Build caches contain reusable intermediates; acceptance evidence is recreated for the revision being reviewed.
