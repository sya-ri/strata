# Independent reactive screen exercise

An independent implementation agent received a messaging-screen task and the Strata skill/API references without the intended API-selection answer or runtime tests. The task supplied a minute clock, sending/loading flags, immutable message history with stable IDs, a caller-owned multiline draft, and a send callback. It required automatic updates and preserved editing/scroll ownership within a 200 by 180 screen.

The first result is preserved unchanged in `integration/docs/src/skillForwardFirst/kotlin/InboxScreen.kt` and compiled in a source set with only `strata-api`. It correctly retained clock/label/enabled projections and list state, used direct source inputs, and restricted Observe to loading structure. No whole-screen observation, polling, screen recreation, or runtime imports were introduced.

The first render exposed a separate authoring pitfall: fixed 160 by 12 constraints were passed to default natural-size single-line Text, including inside the loading Observe. The runtime correctly rejected those constraints. `SkillForwardTest` preserves that failure rather than silently replacing the initial evidence. The skill now explicitly recommends `TextLayout.Multiline()` for reserved text rectangles and explains that direct and literal text share geometry rules.

The corrected `integration/docs/src/skillExamples/kotlin/dev/s7a/strata/integration/docs/skill/InboxScreen.kt` keeps the original update structure and uses multiline text in the two reserved rectangles. Its deterministic runtime test measures clock/sending update boundaries, equal clock projection, row construction bounds for 10,000 messages, stable prepend anchors, and draft/focus/composition retention. Both source sets compile against API alone; the independent runtime harness alone imports diagnostics. Agent availability and nondeterministic code generation are not CI requirements.

## Fresh-author recheck

A different author received two tasks, the improved skill, and its public references, with no earlier outputs, evaluation tests, or intended API-selection answer.
The message editor requires a seconds-based minute clock, sending/loading states, a caller-owned multiline draft, and a 10,000-message history.
The player search requires elapsed minutes, a searching state, a caller-owned query, loading/empty results, and a 10,000-player list.
Both fit a 200 by 180 viewport and preserve input focus and scrolling across independent updates.

The four first implementations are preserved in `integration/docs/src/skillRecheckFirst/kotlin/dev/s7a/strata/integration/docs/recheck`.
Initial compilation and runtime acceptance happened before repository formatting; the formatter subsequently expanded only the two outer Modifier chains across lines, without changing any expression or behavior.
Both first outputs compile against API alone and render successfully without author corrections.
They retain projected sources and dedicated editing/list state, bind changing component inputs directly, and reserve multiline text geometry.
The search status uses a narrow two-source Observe; a nonempty-to-nonempty result update suppresses that status evaluation because its empty projection is unchanged.

`SkillRecheckTest` runs both original definitions using real font metrics and runtime monitoring.
It requires zero dependent UI work for within-minute updates, one direct component evaluation for a changed minute, no history-row evaluation for independent clock/action updates, stable editor focus during composition, and unchanged visible semantics after simultaneous prepend/append.
The measured materialization limits are nine rows in the 68-pixel history viewport and eleven in the 92-pixel search viewport, including partially visible rows and one overscan row per edge.
An initial harness bound omitted the additional partially visible row; the harness was corrected after inspecting the nine/eleven actual evaluations, without modifying either submitted screen.
The API-only classpath gate now checks accepted examples and both independent first-output source sets.

This recheck succeeded on two of two tasks from one fresh author, following the earlier one-task exercise.
It is evidence for these tasks, not a statistically representative success rate for arbitrary models, prompts, or screens.
