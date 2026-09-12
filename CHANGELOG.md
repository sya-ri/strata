# Changelog

Release highlights and upgrade notes are collected here by version.
Keep all Strata artifacts on the same release version.

## Releases

[0.1.6](#v0.1.6) · [0.1.5](#v0.1.5) · [0.1.4](#v0.1.4) · [0.1.3](#v0.1.3) · [0.1.2](#v0.1.2) · [0.1.1](#v0.1.1) · [0.1.0](#v0.1.0)

<a id="v0.1.6"></a>

## 0.1.6 - 2026-09-09

Added per-editor TextField/TextArea frames, caret and IME colors, and exact fractional viewport clipping.
Existing appearance defaults remain available.

Custom display-list backends must handle `DrawCommand.PushFractionalClip`, balanced by `PopClip`.

<a id="v0.1.5"></a>

## 0.1.5 - 2026-09-08

Added direct StateSource component inputs, retained projections, Observe regions, and optional render monitoring.
Keep editing and navigation state outside reevaluation; custom state-observation runtimes must honor source identity and the owner-thread frame cutoff.

<a id="v0.1.4"></a>

## 0.1.4 - 2026-09-06

Added uniform `scaleToFit` layout and child transforms, and corrected deferred Fabric layer ordering.
Opaque platform draws still require integer translation and unit scale.

Custom child-transform implementations must invalidate layout when their transform changes without remeasurement.

<a id="v0.1.3"></a>

## 0.1.3 - 2026-09-01

Added Tab/Shift+Tab focus traversal, shared `onActivate` actions, bounded host resource-image reuse, and metadata-driven release/CI workflows.
Focused handlers may still consume Tab; platform adapters must allow successful resource-image resolutions to be reused for the host lifetime.

<a id="v0.1.2"></a>

## 0.1.2 - 2026-08-30

Image and PlayerHead use retained sampled-image presentation so placement changes can reuse native textures.
No public members changed from 0.1.1; custom backends must support or explicitly reject their `DrawCommand.SampledImage` output.

<a id="v0.1.1"></a>

## 0.1.1 - 2026-08-30

Added Minecraft 1.20 support, resource fonts, Unicode and multiline editing, FlowRow, Canvas, TiledImage, captured pointer input, and integer player-head scaling.
Custom exhaustive visitors must handle `UiText.WithFont` and `DrawCommand.SampledImage`.

<a id="v0.1.0"></a>

## 0.1.0 - 2026-08-25

Initial API-only screen definitions, components and modifiers, retained/headless runtimes, and versioned Fabric adapters.
This release's font path supports printable ASCII only.
