# UI sessions

`runtime:core` owns a tested internal session that coordinates retained trees, local state, revisioned sources, pointer input, and coroutine work.
It is not yet a public screen-definition API.
Keeping this orchestration in the Minecraft-independent runtime gives headless and game adapters the same lifecycle and state semantics.

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

The common `runtime:minecraft` adapter consumes a one-shot screen definition and a complete immutable profile.
Definition close and host transfer race atomically, and a transferred host exposes only owner-thread metadata, lifecycle, fixed-viewport frames, and typed pointer, keyboard, committed-character, and preedit input.
Its screen-content callback is an ordinary `UiScope`, while the host installs its selected Minecraft profile behind that callback for top-level Minecraft components and modifiers.
Callers therefore declare `Text`, `TextField`, `TextArea`, `Button`, `Checkbox`, `CycleButton`, `Slider`, `Tab`, `ScrollArea`, `Scrollbar`, `VirtualList`, `SelectionList`, `Image`, `Canvas`, `Slot`, `PlayerHead`, `LoadingIndicator`, and `ProgressBar` directly without an additional root builder or an explicit Minecraft context receiver.
Application code emits those components directly and composes profile-backed `menuBackground()`, `containerBackground(rows)`, or immutable `imageBackground(image, scale)` behavior into ordinary modifier chains; screen definitions, `Text`, and `Button` accept `String` literals without requiring `UiText.Literal`, while typed overloads retain unresolved `UiText` values when needed.
The fixed-height profile-backed Button owns appearance, hover visuals, and enabled semantics, while reusable pointer, keyboard, text-input, preedit, focus, press, release, move, drag, scroll, and hover actions are active modifiers shared with other component kinds.
TextField owns the verified EditBox sprites, typed profile-backed text colors, insert or append cursor, Unicode scalar editing, and semantics while caller-owned owner-thread `TextFieldState` owns the value and positive UTF-16 maximum length.
The ordinary field is 200 by 20, while the explicit-size overload applies the native one-pixel nine-slice border and integer-centered glyph row to any extent of at least 9 by 9.
TextArea shares the typed frame assets and font layout, with canonical LF values, visual-line cursor affinity, a constrained inner viewport, and owner-thread `TextAreaState`.
Its state owns one stable vertical `ScrollState` that an independent `Scrollbar` may observe; one state may attach to only one editor at a time.
Mutable editor, focus, preedit, current-layout, and invalidation ownership live only in retained nodes, never in reusable immutable element descriptions.
Focused input modifiers run before either built-in editor, so consuming a typed event overrides its default action and ignoring the event permits the editor to handle it.
Text, TextField, and TextArea accept resource-pack font IDs, and `UiText.withFont` carries the same selection through composed labels.
Existing Text overloads remain single-line; typed `TextLayout.Multiline` uses parent width constraints and shares line breaking with TextArea.
Font metrics drive measurement, drawing, cursor placement, and scrolling together; Unicode glyph availability follows the selected resource pack.
Inline preedit text has its own caret and focused block and does not change the caller's value until committed input arrives.
It does not reproduce the native IME popup or platform candidate window.
See [Text and text input](text.md) for font selection, Unicode boundaries, and the compatibility limits.
ScrollArea owns the active Minecraft profile's menu-list background, child clipping, separators, wheel behavior, and retained offset through caller-owned `ScrollState`.
An independently placed Scrollbar observes the same state and owns the track sprites, proportional thumb, and thumb dragging; a caller may omit it or place it away from the viewport while preserving the native background-to-content-to-overlay paint order.
The container-background modifier owns the verified row-dependent generic chest geometry and two native texture regions, while Slot owns the exact 18 by 18 pointer region, optional 16 by 16 content root, and back-content-front hover layers.
The Fabric-backed `Slot(bind = ...)` form accepts `Slots.playerInventory(index)`, a logical `Slots.container(index)`, or the raw-menu escape hatch `Slots.activeMenu(index)`; it polls the current authoritative menu before each frame, inserts native item rendering at the Slot's ordered item phase, and sends pointer transactions through Minecraft's container-input operation instead of mutating inventory storage.
The loaded integration opens storage on the integrated server and proves player inventory, a custom `SimpleContainer`, and ender-chest pickup and restoration through the same binding protocol.
That live overload is intentionally unavailable to portable-only hosts because arbitrary `ItemStack` models are native version assets; the optional-content overload remains the headless-compatible Slot contract.
Button does not install keyboard focus or activation implicitly; callers compose those policies from the shared modifiers when required.
The common component boundary exposes only structural resource-pack identifiers and detached immutable pixels, not resource-manager objects, native Minecraft values, renderers, input mappers, or task facilities.
Client and server code may share a `ResourceId`; only the versioned client resolves its pixels through the active resource-pack stack before building an `Image` or image-background modifier.
One common host memoizes each admitted resource-image resolution by structural `ResourceId` across immediate and deferred component evaluation, while direct pixel sources bypass platform resolution.
The fixed host cache admits at most 512 identifiers and 128 MiB of straight-RGBA8 pixels without eviction; results beyond either admission limit and all failed resolutions bypass retention.
All access is owner-thread confined, detachment preserves the cache with the host, and terminal host cleanup clears it before closing the platform.
Resolution remains lazy, so the first use observes the then-active pack stack, but an admitted image stays fixed until host close; a new host is required to resolve replacement pixels reliably.
Image may retain either the complete immutable asset or one nonempty contained source rectangle, allowing sprite-atlas regions to map to an independent destination size without copying pixels or introducing a purpose-specific component.
The Fabric adapter snapshots the current selected player skin from either its resource-backed default path or registered downloaded texture; `PlayerHead` then renders the native face layer followed by the optional hat layer without retaining a player or platform texture.

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

`canvasSource(frames)` uses this protocol for immutable `DrawImage` revisions without introducing a streaming or timestamp protocol.
Its any-thread observer only enqueues the newest revision; both timed and untimed frames drain it before paint-cache reuse.
An image's pixel extent may change while the Canvas destination remains its explicit positive logical size.
The node owns only its attachment binding and stops observing on source replacement, session detach, or terminal cleanup; it never closes the caller-owned source.

## Frames and input

Attach creates a retained tree when necessary, activates one task generation, applies pending source values, rebuilds dirty content once, and resumes attachment-scoped resources.
A frame applies another source cutoff, rebuilds dirty content at most once, then measures, lays out, paints, and collects semantics in order.
Its size, drawing commands, and semantics entries are immutable defensive snapshots.

Pointer, keyboard, committed-character, and preedit input are ignored until one complete frame has committed.
Afterward it targets the most recently committed tree.
State changed by an input callback becomes visible to retained UI behavior after the next successful frame.
Detach cancels active pointer capture, emits exit for active pointer-hover observers, clears focused ownership, invalidates the committed-frame marker, and retains the tree and state.
The input pipeline retains at most one captured entry and its starting button, releases that reference before matching-release or cancellation callbacks, and cancels before entry disposal as well as on session input reset.
Captured move and matching-button drag or release delivery uses the latest committed layout even outside ancestor clips, while other buttons, scrolling, and hover preserve ordinary hit testing.
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
