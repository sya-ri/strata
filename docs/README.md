# Strata documentation

Strata lets applications describe Minecraft interfaces with reusable components and caller-owned state.
Use these guides to build a screen, the references to extend the public contracts, and the development documents to change Strata itself.
For installation and a complete opening example, start with the [project README](../README.md).

These pages follow the development branch.
For published artifacts, use the documentation at the matching [release tag](https://github.com/sya-ri/strata/releases); a feature documented here may require building the current sources.

## Build an interface

| Read | Use it to |
| --- | --- |
| [Screens and state](guides/screens-and-state.md) | Declare a UI, switch Screen/HUD presentation, control input, own state, and connect resources. |
| [Paper and Folia screens](guides/paper.md) | Install the server plugin, open player screens, and register matching client extensions. |
| [Velocity screens](guides/velocity.md) | Own screens on a proxy, queue UI state changes, and coexist with Paper backends. |
| [Layout](guides/layout.md) | Choose containers and control spacing, sizing, wrapping, and alignment. |
| [Modifiers](guides/modifiers.md) | Add sizing, backgrounds, input, focus, and parent-scope behavior. |
| [Text and editing](guides/text.md) | Display labels, edit single-line or multiline values, and select fonts. |
| [Font resources](guides/fonts.md) | Load resource fonts for offline rendering and configure their public limits. |
| [Canvas](guides/canvas.md) | Embed external CPU images or native drawing output. |
| [Tiled images](guides/tiled-images.md) | Connect tile sources, navigation state, and content-position overlays. |
| [Headless rendering](guides/headless.md) | Render and inspect portable UI output without opening Minecraft. |
| [Browser runtime example](../README.md#build-a-web-screen-from-source) | Build initial HTML and try the supported native DOM components. |

Browse the [component overview](reference/components.md) for images and compiled examples in one place.
The [complete screen examples](examples/screens.md) show how those components work together.
The [interactive web demos](https://gh.s7a.dev/strata/demos/) let you try state changes, progress, and keyed reordering in a browser.
The [animated README demo](readme-demo/README.md) follows a fixed player list from natural sizing through width, weight, and linked scrolling.

## Look up a contract

- [Dokka API reference](https://gh.s7a.dev/strata/) contains declarations, signatures, and KDoc.
- [Runtime compatibility](reference/compatibility.md) lists the supported targets, artifacts, and Java requirements.
- [Layout contract](reference/layout.md) defines measurement, wrapping, weight allocation, and integer placement.
- [Element SPI](reference/element-spi.md) describes custom primitives, retained phases, and ownership.
- [Modifier SPI](reference/modifier-spi.md) describes active modifier nodes and typed parent data.
- [Declaration projection](reference/declaration-projection.md) defines optional typed properties, remote action bindings, and retained resource projection.
- [Platform events](reference/platform-events.md) defines Paper and Velocity client readiness and applied UI lifecycle notifications.
- [Remote protocol](reference/remote-protocol.md) defines negotiation, atomic updates, input ordering, resource bounds, and extension ownership.
- [External state sources](reference/state-sources.md) specifies revisioned observation across threads.

## Changelog

See the [changelog](../CHANGELOG.md) for release summaries and links to detailed changes and upgrade notes.

## Develop Strata

Start with [Contributing](../CONTRIBUTING.md) and the [architecture](development/architecture.md).
Use [build and verification](development/build.md) for local commands, [CI](development/ci.md) for workflow ownership, [release](development/release.md) for publication, and [documentation maintenance](development/documentation.md) for generated content.
Runtime contributors should also read [UI sessions](development/ui-sessions.md), [rendering](development/rendering.md), [performance](development/performance.md), and [font verification](development/font-verification.md).
Use [render monitoring](development/render-monitoring.md) to verify actual update work and the [independent screen exercise](development/skill-forward-evaluation.md) to maintain the skill's authoring guidance.
Follow [Minecraft adapter development](development/minecraft-versions.md) when changing version support.

The [publication introductions](publication/dokka-module.md) and the [Modrinth](publication/modrinth-project.md), [CurseForge](publication/curseforge-project.md), and [Hangar](publication/hangar-project.md) bodies serve their respective distribution surfaces.
They are maintained through the [documentation ownership rules](development/documentation.md#documentation-ownership).
