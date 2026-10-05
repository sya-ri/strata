# Independent reactive screen exercise

An independent implementation agent received a messaging-screen task and the Strata skill/API references without the intended API-selection answer or runtime tests. The task supplied a minute clock, sending/loading flags, immutable message history with stable IDs, a caller-owned multiline draft, and a send callback. It required automatic updates and preserved editing/scroll ownership within a 200 by 180 screen.

The first result is preserved unchanged in `integration/docs/src/skillForwardFirst/kotlin/InboxScreen.kt` and compiled in a source set with only `strata-api`. It correctly retained clock/label/enabled projections and list state, used direct source inputs, and restricted Observe to loading structure. No whole-screen observation, polling, screen recreation, or runtime imports were introduced.

The first render exposed a separate authoring pitfall: fixed 160 by 12 constraints were passed to default natural-size single-line Text, including inside the loading Observe. The runtime correctly rejected those constraints. `SkillForwardTest` preserves that failure rather than silently replacing the initial evidence. The skill now explicitly recommends `TextLayout.Multiline()` for reserved text rectangles and explains that direct and literal text share geometry rules.

The corrected `integration/docs/src/skillExamples/kotlin/dev/s7a/strata/integration/docs/skill/InboxScreen.kt` keeps the original update structure and uses multiline text in the two reserved rectangles. Its deterministic runtime test measures clock/sending update boundaries, equal clock projection, row construction bounds for 10,000 messages, stable prepend anchors, and draft/focus/composition retention. Both source sets compile against API alone; the independent runtime harness alone imports diagnostics. Agent availability and nondeterministic code generation are not CI requirements.

## Fresh-author recheck

The API-only first submissions under `integration/docs/src/skillRecheckFirst/kotlin/dev/s7a/strata/integration/docs/recheck` implement a message editor and player search in a 200 by 180 viewport.
Both receive changing status, caller-owned editing state, and 10,000 keyed rows; their initial outputs compile and render without author corrections.
Keep these submissions unchanged as evidence.

`SkillRecheckTest` checks equal minute projections, independent clock/action updates, focus during composition, prepend/append semantics, and bounded visible-row creation.
The history and search viewports materialize at most nine and eleven rows respectively, including partial and overscan rows.
All accepted and original-example source sets compile against `:api` alone.
This is evidence for these tasks, not a general model success rate; CI does not require an agent run.

## Repeating independent authoring exercises

Use a fresh empty consumer project and a fresh author for each recheck.
Provide only the application task, the packaged skill and linked public contracts, and the selected API dependency.
Do not supply a reference implementation, expected API choices, previous failures, reviewer notes, or runtime assertions to the author.
The author may compile and test its own models before submitting; snapshot the complete first submission and its report before external review, with source and supplied-skill hashes.

Review the resulting component tree and state ownership, then execute the unchanged submission in a separate production-host harness.
Exercise supported viewport endpoints, structural states, editing, focus/composition, keyed updates, scrolling, detach/reattach, and idle/update diagnostics as applicable.
For a retained primitive, also test local coordinates, clipping and tiny/zero geometry, confirmed pointer capture, keyboard behavior, semantics, update invalidation, and resource lifetime.
Keep host instrumentation outside the API-only application source set.
Use the shared Performance testkit when collecting timings; render assertions and diagnostic work counts do not establish a timing improvement.

Distinguish application failures from mistakes in the external harness.
Preserve both original reports and corrections instead of modifying an independent submission to make it pass.
Refine the skill only for a general contract exposed by the failure, then repeat the affected task with a new author.
Do not add blanket architecture heuristics to static rules when resolved symbols cannot establish the contract.

The broader exercises include a handheld application router, a large activity dashboard, a workshop form using standard controls and an independently updated Canvas, two applications sharing content-slot compositions, and two uses of a custom retained time-series primitive.
The fresh workshop and reusable-component rechecks rendered at both supported viewport endpoints after the geometry guidance was clarified.
The custom primitive exercised capture outside its bounds, keyboard selection, 100,000-sample updates, stable node reuse, and tiny/zero leaf geometry.
These finite exercises cover selected components and modifiers; they do not establish exhaustive API, platform, accessibility, or model coverage.

The preserved failures reinforced three existing contracts: explicit list viewports and fixed control sizes must satisfy allocated constraints; a single-child Observe passes constraints to its child; profile-specific controls can impose additional geometry limits.
`SkillViewportContractTest` and the API-only `ListViewportExample` provide deterministic checks for viewport allocation and the Minecraft control-width boundary.
The opt-in [authoring checks](../guides/authoring-checks.md) separately test resolved retained-state creation and unused modifier parameters against the real public API.
Keep independent author runs out of CI; deterministic examples and rule regressions remain ordinary checks.
