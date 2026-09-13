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
