# UI sessions

`runtime:core` owns a tested internal session that coordinates retained trees, local state, revisioned sources, pointer input, and coroutine work.
It is not yet a public screen-definition API.
Keeping this orchestration in the Minecraft-independent runtime gives headless and game adapters the same lifecycle and state semantics.

## Runtime adapter bridge

`dev.s7a.strata.runtime.spi` provides a public but opt-in runtime adapter bridge for platform runtimes that need to drive this session.
It is not an application screen-definition API and does not expose coroutines, state declarations, source bindings, `UiSession`, `UiFrame`, session state, or task-failure decision types.
`attach`, `detach`, `frame`, pointer input, focused keyboard and text input, and `close` are synchronous calls that must already run on the construction and owner thread.
The synchronous bridge exposes no task-launching or dispatcher facility.
Its content lambda is evaluated during the first attach, after which the retained tree handles frames and input until terminal failure or close.
Each successful frame owns immutable defensive snapshots of size, drawing commands, and semantics, and all input is ignored until the first successful frame commits.
The bridge delegates exact primary-failure identity, suppression order, lifecycle transitions, and cleanup-once behavior to the retained session.
It retains the content lambda while created, attached, or detached and releases it before cleanup callbacks after terminal failure or close.
Session detach retains the active `UiTree` and its node ownership; it clears active hover and focused ownership before clearing the committed-frame marker, without rerunning node attach or detach lifecycle callbacks until terminal close.

The common `runtime:minecraft` adapter consumes a one-shot screen definition and a complete immutable profile.
Definition close and host transfer race atomically, and a transferred host exposes only owner-thread metadata, lifecycle, fixed-viewport frames, and typed pointer, keyboard, committed-character, and preedit input.
Its screen-content callback provides an implicit Minecraft component receiver around ordinary `buildUi` scopes.
Application code emits `MenuBackground`, `Text`, `Button`, and `Scroll` directly; `Text` and `Button` accept either `String` literals or unresolved `UiText` values.
The fixed-size profile-backed Button owns appearance, hover visuals, and enabled semantics, while reusable pointer, keyboard, text-input, preedit, focus, press, release, move, drag, scroll, and hover actions are active modifiers shared with other component kinds.
Scroll owns the active 26.2 menu-list background, child clipping, separators, scrollbar sprites, retained wheel offset, proportional thumb dragging, and the native background-to-content-to-overlay paint order.
Button does not install keyboard focus or activation implicitly; callers compose those policies from the shared modifiers when required.
The common component boundary does not expose resources, native Minecraft values, renderers, input mappers, or task facilities.

## Ownership and lifecycle

A session captures the thread that creates it.
Lifecycle operations, delegate access, frame production, and every input dispatch are confined to that owner thread.
Revisioned source callbacks are the exception: they may arrive on any thread, only replace a lock-protected pending snapshot, and never execute session work.

The lifecycle is:

| State | Retained ownership | Active task generation | Legal next transition |
| --- | --- | --- | --- |
| `Created` | declarations and subscriptions | no | attach or close |
| `Attached` | tree, declarations, and subscriptions | yes | detach, fail, or close |
| `Detached` | tree, declarations, and subscriptions | no | attach or close |
| `Failed(cause)` | cleanup already attempted | no | close |
| `Closed` | none | no | none |

Invalid transitions fail before changing the lifecycle.
An unrecoverable content, retained-tree, pipeline, or task failure records the exact primary `Throwable` in `Failed` and attempts cleanup.
Closing a failed session changes only the lifecycle to `Closed`, because failure cleanup has already run.
Repeated close after `Closed` is an owner-thread no-op.

## Local and external state

Local state and external source bindings are declared only in `Created`, before content evaluation begins.
Their delegates may be read in `Created`, `Attached`, and `Detached`.
A changed local value marks content dirty; assigning an equal value does not.
Mutating an already-retained mutable value in place cannot be detected and therefore does not invalidate content.

Local writes are accepted outside an active session operation, from pointer callbacks, and from the current task-failure handler.
They are rejected during attach, content evaluation, reconciliation, lifecycle callbacks, frame pipelines, detach, binding establishment, and cleanup.
This keeps declarative measure, layout, paint, and semantics work free from state mutation while preserving event-driven updates.

Value equality can execute arbitrary application code.
The session guards local and bound-value comparisons so equality cannot recursively access delegates, declare state, bind a source, or re-enter lifecycle work.
A throwing comparison leaves local state and its dirty marker unchanged and releases the comparison guard.
A source may still publish a later revision from equality; that callback only enqueues the revision for the next cutoff.

Each source subscription returns an initial snapshot from the same linearization point that installs its observer.
Callbacks that race or precede the return from `subscribe` are merged with that snapshot by revision.
The owner thread commits the newest pending snapshot at a frame cutoff and evaluates value equality after releasing the binding lock.
A callback arriving after the cutoff remains pending for the following frame.

## Frames and input

Attach creates a retained tree when necessary, activates one task generation, applies pending source values, and rebuilds dirty content once.
A frame applies another source cutoff, rebuilds dirty content at most once, then measures, lays out, paints, and collects semantics in order.
Its size, drawing commands, and semantics entries are immutable defensive snapshots.

Pointer, keyboard, committed-character, and preedit input are ignored until one complete frame has committed.
Afterward it targets the most recently committed tree.
State changed by an input callback becomes visible to retained UI behavior after the next successful frame.
Detach emits exit for active pointer-hover observers, clears focused ownership, invalidates the committed-frame marker, and retains the tree and state.

## Coroutine generations

The session exposes one stable screen-scope facade internally, but each attachment supplies it with a fresh `SupervisorJob` generation.
Created, detached, failed, and closed contexts contain an already-cancelled job, so launches in those states never start their body.
Detach, failure, and close mark the current generation stale before cancelling its job.
Stale cancellation code cannot read or write session state or launch into a later generation.

The caller supplies a runtime-owned dispatcher that always queues work onto the session's construction thread.
The dispatcher must not run a submitted block inline and must remain serviced while cancellation finalizers can resume.
Detach and close request cancellation but do not synchronously join arbitrary child work.
The current internal contract therefore requires a dispatcher whose lifetime is owned by the surrounding runtime rather than by one screen.

The supported off-thread pattern starts in the screen scope and uses `withContext` for the worker section.
Continuation after `withContext` returns through the generation dispatcher to the owner thread.
Passing another dispatcher directly to `launch` replaces the generation dispatcher for that coroutine body, so session state access from that worker fails owner-thread validation.
Replacing the scope job or exception handler is outside the session contract because it would bypass lifecycle ownership or failure policy.

## Task and cleanup failures

A `SupervisorJob` isolates sibling tasks.
Non-cancellation root failures are queued to the owner thread and passed to the configured typed decision handler.
`Continue` consumes the failure and keeps the current generation alive.
`FailSession` poisons a still-current session, cancels the generation, and cleans its resources.

A failure handler runs as a serialized session operation.
It may read and update current local state, but lifecycle reentry and new declarations are rejected.
If the handler throws, the task failure remains primary and a distinct handler failure is suppressed on it.

A failure may become stale while its owner-thread delivery is waiting in the dispatcher.
The handler still observes it under the stale generation, so it cannot access a reattached or closed session.
Stale `Continue` consumes the failure without mutation.
Stale `FailSession`, or a stale throwing handler, surfaces the task failure from the owner-dispatcher runnable without changing or cleaning the current session.

Terminal cleanup first closes source subscriptions in declaration order and then closes the retained tree.
It attempts every resource exactly once even when callbacks fail.
The original failure instance remains primary, and later distinct handler, subscription, detach, and dispose failures are suppressed in observation order.
