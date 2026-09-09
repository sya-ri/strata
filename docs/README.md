# Strata documentation

Strata lets applications describe Minecraft interfaces with reusable components and caller-owned state.
Use these guides to build a screen, the references to extend the public contracts, and the development documents to change Strata itself.
For installation and a complete opening example, start with the [project README](../README.md).

## Build an interface

| Read | Use it to |
| --- | --- |
| [Screens and state](guides/screens-and-state.md) | Declare and open a screen, own its state, and connect application resources. |
| [Layout](guides/layout.md) | Choose containers and control spacing, sizing, wrapping, and alignment. |
| [Modifiers](guides/modifiers.md) | Add sizing, backgrounds, input, focus, and parent-scope behavior. |
| [Text and editing](guides/text.md) | Display labels, edit single-line or multiline values, and select fonts. |
| [Font resources](guides/fonts.md) | Load resource fonts for offline rendering and configure their public limits. |
| [Canvas](guides/canvas.md) | Embed external CPU images or native drawing output. |
| [Tiled images](guides/tiled-images.md) | Connect tile sources, navigation state, and content-position overlays. |
| [Headless rendering](guides/headless.md) | Render and inspect portable UI output without opening Minecraft. |

Browse the [component overview](reference/components.md) for images and compiled examples in one place.
The [complete screen examples](examples/screens.md) show how those components work together.

## Look up a contract

- [Dokka API reference](https://gh.s7a.dev/strata/) contains declarations, signatures, and KDoc.
- [Runtime compatibility](reference/compatibility.md) lists the supported targets, artifacts, and Java requirements.
- [Element SPI](reference/element-spi.md) describes custom primitives, retained phases, and ownership.
- [Modifier SPI](reference/modifier-spi.md) describes active modifier nodes and typed parent data.
- [External state sources](reference/state-sources.md) specifies revisioned observation across threads.
- [Changelog](../CHANGELOG.md) and its linked release notes describe versioned changes and migration.

## Develop Strata

Start with [Contributing](../CONTRIBUTING.md) and the [architecture](development/architecture.md).
Use [build and verification](development/build.md) for local commands, [CI](development/ci.md) for workflow ownership, [release](development/release.md) for publication, and [documentation maintenance](development/documentation.md) for generated content.
Runtime contributors should also read [UI sessions](development/ui-sessions.md), [rendering](development/rendering.md), [performance](development/performance.md), and [font verification](development/font-verification.md).
Use [render monitoring](development/render-monitoring.md) to verify actual update work and the [independent screen exercise](development/skill-forward-evaluation.md) to maintain the skill's authoring guidance.
Follow [Minecraft adapter development](development/minecraft-versions.md) when changing version support.

The [publication introductions](publication/dokka-module.md) and [Modrinth body](publication/modrinth-project.md) serve their respective distribution surfaces.
They are maintained through the [documentation ownership rules](development/documentation.md#documentation-ownership).
