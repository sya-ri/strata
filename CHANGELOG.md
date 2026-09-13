# Changelog

Each version below summarizes the release and links to its detailed changes and upgrade notes.
Keep all Strata artifacts on the same release version.

## 0.1.6 - 2026-09-09

Per-editor TextField/TextArea frames, caret and IME colors; exact fractional viewport clipping. Existing appearance defaults remain available.

[Detailed changes and upgrade notes](docs/releases/v0.1.6.md)

## 0.1.5 - 2026-09-08

Direct StateSource component inputs, retained projections, Observe regions, and optional render monitoring. Keep editing and navigation state outside reevaluation.

[Detailed changes and upgrade notes](docs/releases/v0.1.5.md)

## 0.1.4 - 2026-09-06

Uniform `scaleToFit` layout and child transforms, plus corrected deferred Fabric layer ordering. Opaque platform draws still require integer translation and unit scale.

[Detailed changes and upgrade notes](docs/releases/v0.1.4.md)

## 0.1.3 - 2026-09-01

Tab/Shift+Tab focus traversal, shared `onActivate` actions, bounded host resource-image reuse, and metadata-driven release/CI workflows.

[Detailed changes and upgrade notes](docs/releases/v0.1.3.md)

## 0.1.2 - 2026-08-30

Image and PlayerHead use retained sampled-image presentation so placement changes can reuse native textures. No public members changed from 0.1.1.

[Detailed changes and upgrade notes](docs/releases/v0.1.2.md)

## 0.1.1 - 2026-08-30

Minecraft 1.20 support, resource fonts, Unicode and multiline editing, FlowRow, Canvas, TiledImage, captured pointer input, and integer player-head scaling. Custom exhaustive visitors must handle `UiText.WithFont` and `DrawCommand.SampledImage`.

[Detailed changes and upgrade notes](docs/releases/v0.1.1.md)

## 0.1.0 - 2026-08-25

Initial API-only screen definitions, components and modifiers, retained/headless runtimes, and versioned Fabric adapters. This release's font path supports printable ASCII only.

[Detailed changes and upgrade notes](docs/releases/v0.1.0.md)
