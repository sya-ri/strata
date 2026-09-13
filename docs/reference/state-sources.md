# External state sources

The `api` module exposes a small, platform-neutral contract for state owned outside Strata.
It does not depend on coroutines or a runtime implementation.

## Revisioned snapshots

`StateRevision` is a non-negative `Long` value with total numeric ordering, including zero and `Long.MAX_VALUE`.
Once a source publishes `Long.MAX_VALUE`, it cannot publish a later snapshot and must fail without mutating its current observation or notifying observers.
`StateSnapshot<T>` is an immutable carrier containing a source-assigned revision and its value.
It does not copy the value or make a mutable value deeply immutable; mutation of a referenced mutable value remains observable.
Snapshot equality compares both fields using Kotlin value equality.

Sources must publish revisions strictly later than the initial snapshot and every preceding callback for each subscription.
Equal revisions within one source identify the same logical observation across subscriptions; a source must not reuse an equal revision for a different value.
Revisions are comparable only within one source instance and must not wrap after `Long.MAX_VALUE`.
Consumers use revisions to reject stale observations; they do not infer ordering from values.

## Atomic subscription

Implement covariant `StateSource<out T>` with one `subscribe(observer)` operation.
That operation registers the observer and captures the latest snapshot as one linearizable action, then returns a `StateSubscription<T>` containing that initial snapshot.
Concurrent subscriptions are supported.
There is intentionally no separate current-value read, because a read followed by subscription would leave an update gap.

A source may invoke the observer before `subscribe` returns.
Every observation published after the subscription linearization point is delivered without omission while the subscription remains open, including observations racing the return from `subscribe`.
Callbacks may run on any thread, and a source serializes callbacks for one subscription so they never overlap or re-enter and strictly increase in revision.
An observer must return normally.
Throwing is a subscriber contract violation; its exception may escape on the delivery thread, and subscription-state guarantees after the violation are unspecified.
The source owns its state and subscription machinery; the subscriber owns the returned handle.

`StateSubscription.close()` is thread-safe and idempotent.
After `close` returns, a conforming source starts no new callback for that subscription, although a callback already in progress may finish.
A notification whose delivery races `close` may be delivered or suppressed.
Concurrent close callers wait for the one source close action to finish.
If that action fails, every close caller observes the same failure and no successful-closure guarantee is made.
If establishment fails before the subscription linearization point, it transfers no subscription and starts no observer callback.

## Use in a screen

`StateSource<T>.map(transform: (T) -> R): StateSource<R>` creates a lazy read-only projection. Import `dev.s7a.strata.state.map`.
It supports nullable values and chained projections. Keep its identity beside the caller-owned source, outside callbacks that Strata reevaluates.
Transforms must be deterministic, inexpensive, and free from side effects and I/O.

Ordinary `subscribe` transforms the atomic initial snapshot and every later delivered snapshot, retaining the original revision and ordering even when outputs compare equal.
A close racing an in-progress transform prevents a new downstream callback after successful close. A callback already entered may finish.
An initial transform failure releases the upstream subscription and preserves cleanup failures as suppressed exceptions; later observer/transform failures are ordinary subscriber contract violations.

The UI runtime recognizes the projection dependency graph and shares one external subscription when the original and its projections are consumed together.
It freezes original inputs before deriving values, calculates each admitted projection once per changed committed input, and compares outputs using `==`.
Equal outputs stop propagation to later projections and UI consumers. This does not promise that a mapper never runs: changed inputs may require computation to determine equality.
Source identity replacement and changed parent declarations remain distinct reasons to reevaluate; callback captures cannot be compared semantically.
Only current dependencies and latest snapshots are retained; failure, replacement, final removal, and close release the corresponding ownership.

[Screens and state](../guides/screens-and-state.md) distinguishes caller-owned component state from external sources.
[Canvas](../guides/canvas.md) and [tiled images](../guides/tiled-images.md) show public source consumers.
Runtime implementers commit their queued observations through the [session frame cutoff](../development/ui-sessions.md#local-and-external-state).
