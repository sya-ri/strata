# UI sessions

This is the internal session contract for runtime implementers.
`runtime:core` coordinates retained trees, local state, revisioned sources, input, and coroutine work so adapters share one lifecycle and failure model.
Application authors use [ScreenDefinition and component state](../guides/screens-and-state.md); the local delegates and coroutine generations described here are not a public screen-definition API.

## Retained observed regions

Observe, direct-source components, and observed activation modifiers use one ObservedSourceRegistry in the session-owned UiTree.
The registry indexes sources by reference identity and retains each live source's committed, pending, and captured snapshots plus its subscription carrier, and the current source list of each observing node.
Source callbacks only enqueue revisions through the existing UiSessionBinding implementation.
Every binding is captured before any value comparison or node notification; all values are committed before observing nodes are notified.
DerivedStateSource exposes a pure upstream edge to this registry; ordinary mapped subscriptions remain independent adapters outside a UI tree.
After root snapshots commit, the registry walks only affected projection edges, computes each retained projection once for its input, and stops at equal results.
An identity-indexed consumer set coalesces repeated arguments, original/derived inputs, and multiple changed dependencies into one committed value tuple per affected node.
Unrelated consumers receive no tuple or callback.
Observation resources remain with retained nodes through session detachment.
Removing a node immediately removes its observation ownership, while a frame temporarily retains its last source binding until replacement nodes have been synchronized.
Readmission of the same source instance reuses that binding's committed and pending snapshots, even when its last former owner changed key, kind, or parent structure.
Sources still unreferenced at successful frame or attachment completion are closed before the operation returns, including cached-frame returns.
Standalone tree pipeline operations use the same temporary retention boundary and release unused bindings before returning.
Terminal failure or close releases all bindings; cleanup failures preserve the existing primary and suppressed-exception contract.
Deferred source cleanup follows the order in which final references were removed, and same-operation readmission cancels that source's pending release.

StateObserverNode is a privileged capability rather than a concrete-component branch in core.
The reconciler synchronizes its final source list immediately before deferred child materialization and visits parents before descendants.
Updating a DynamicChildrenNode description preserves its existing child set until dynamic reconciliation; an empty immutable description is not a request to dispose retained dynamic children.
Observe caches only its current derived child list and rebuilds it after a committed value or parent callback change.
Repeated geometry passes reuse that list, and identity-equal descriptions skip redundant reconciliation callbacks.
DeferredContentNode exposes pending evaluation separately from phase dirtiness, so evaluation runs before frame-cache selection without forcing measure or layout.
Actual reconciled child differences determine pipeline invalidation.
Transparent direct-source regions retain modifiers on the real primitive, forwarding parent data through ParentDataDelegateNode rather than moving focus or action ownership to a wrapper.
An observed region owns a fresh content scope per evaluation and at most one logical child, retaining input nodes through ordinary type and key matching.

RuntimeUiDiagnosticsOwner delegates optional bounded [render monitoring](render-monitoring.md) through the same session boundary.
Collection remains absent by default; enabled collectors count actual callback execution and retain only detached interval evidence after terminal failure.

## Runtime adapter bridge

`dev.s7a.strata.runtime.spi` provides a public but opt-in runtime adapter bridge for platform runtimes that need to drive this session.
It is not an application screen-definition API and does not expose coroutines, state declarations, source bindings, `UiSession`, `UiFrame`, session state, or task-failure decision types.
`attach`, `detach`, `frame`, pointer input, focused keyboard and text input, input reset, and `close` are synchronous calls that must already run on the construction and owner thread.
The synchronous bridge exposes no task-launching or dispatcher facility.
Its content lambda is evaluated during the first attach, after which the retained tree handles frames and input until terminal failure or close.
Each successful frame owns immutable defensive snapshots of size, drawing commands, and semantics, and all input is ignored until the first successful frame commits.
After that first frame, consecutive pointer, keyboard, and text events may arrive without another frame between them.
Before each event, the session resolves only pending retained measurement and layout using the last committed constraints; clean geometry invokes no measure or layout callbacks.
Dirty measurement also refreshes the retained dynamic children needed by virtual viewports.
Queued source revisions, session content evaluation, animation timestamps, paint, semantics, and frame snapshots still advance only through `frame`.
A future resize is not visible until its frame commits, and a pre-input geometry failure prevents event dispatch and follows the existing poison and cleanup contract.
This shared behavior applies to Minecraft hosts in both headless tests and Fabric event bursts; low-level `UiTree` users still explicitly measure and lay out dirty geometry before dispatch.
The bridge delegates exact primary-failure identity, suppression order, lifecycle transitions, and cleanup-once behavior to the retained session.
It retains the content lambda while created, attached, or detached and releases it before cleanup callbacks after terminal failure or close.
Session detach retains the active `UiTree` and its node ownership; it cancels pointer capture and clears active hover and focused ownership before clearing the committed-frame marker, without rerunning node attach or detach lifecycle callbacks until terminal close.
`SessionAttachmentNode` adds a separate resource lifetime for retained nodes whose observations cannot remain active while detached.
After reconciliation, attachment resumes these nodes in effective parent-first order; the callback tolerates resources already acquired by ordinary `LifecycleNode.attach`.
Session detach suspends every such node in reverse-sibling descendant-first order even before the first successful frame, while retaining node identity and externally owned sources.
Suspension clears active references before fallible cleanup, and terminal lifecycle cleanup remains safe after an earlier suspension.
The opt-in `resetInputState` bridge gives native window-blur and input-reset handlers the same capture, hover, and focus cleanup without detaching the session or invalidating its committed frame.

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

The subscriptions retained in `Detached` are session-declared bindings; attachment-scoped node bindings suspend independently through `SessionAttachmentNode`.
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
The owner thread first captures every session binding and every retained `FrameCutoffNode`, then commits the captured observations before content reconciliation.
Capture cannot invoke caller value equality or publish observations; commit evaluates session-bound value equality after releasing the binding lock.
A callback arriving after the cutoff remains pending for the following frame.
Each participating binding retains at most one transaction-local captured observation between these two phases, in addition to its committed and latest pending state.
Sources newly attached or replaced during reconciliation may paint their subscription's initial snapshot, but later callbacks wait for the next frame cutoff.

Public source consumers follow the same cutoff and attachment contracts; see [Canvas](../guides/canvas.md#cpu-sources) for the externally owned image-source case.

## Frames and input

Attach creates a retained tree when necessary, activates one task generation, applies pending source values, rebuilds dirty content once, and resumes attachment-scoped resources.
A frame applies another source cutoff, rebuilds dirty content at most once, then measures, lays out, paints, and collects semantics in order.
Its size, drawing commands, and semantics entries are immutable defensive snapshots.

Pointer, keyboard, committed-character, and preedit input are ignored until one complete frame has committed.
Afterward it targets the most recently committed tree.
State changed by an input callback becomes visible to retained UI behavior after the next successful frame.
Focused keyboard handlers receive each event before traversal, so only an ignored Tab `Press` moves focus; Shift reverses the cyclic parent-before-child and declared sibling paint order, while other modifier bits leave its direction unchanged.
Candidates must accept focus and intersect the root viewport plus every ancestor child clip, excluding clipped VirtualList overscan rows.
A placed current owner remains focused while clip-hidden and continues receiving ordinary focused input, but explicit Tab moves to a visible candidate.
Removing or unmaterializing that owner clears focus without stable-key reacquisition; the next forward or reverse Tab starts at the first or last currently eligible owner.
An unambiguous placed `initialFocus` request applies after layout whenever the tree has no owner, including a newly opened screen or reattachment after focus-clearing detach; focus never transfers from a replaced screen into its successor.
Every Enter or Space `Press` that reaches a focused `onActivate` node, including repeats, invokes its action, while its false enabled overload contributes no pointer, keyboard, focus, or action reference.
Detach cancels active pointer capture, emits exit for active pointer-hover observers, clears focused ownership, invalidates the committed-frame marker, and retains the tree and state.
Captured input follows the [Element SPI](../reference/element-spi.md#paint-input-and-semantics); session detach and input reset cancel it even while nodes remain retained.
Input reset is owner-thread confined, preserves committed pixels and retained ownership, and prohibits session-state mutation from its cleanup callbacks.
Capture, hover, and focus cleanup are all attempted when an earlier callback throws; the original failure remains primary and distinct later failures are suppressed in observation order.

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
