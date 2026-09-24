# Remote UI protocol

`runtime:remote` owns the JVM wire model and sessions without depending on Paper, Velocity, or mapped Minecraft classes.
The hosting Paper or Velocity plugin owns declarations, models, handlers, and authoritative state; the client owns retained layout, rendering, focus, hover, pointer capture, scroll gestures, and IME composition.
The [declaration projection SPI](declaration-projection.md) is the optional platform-neutral boundary between those responsibilities.

## Connection and messages

The Fabric adapter advertises `strata:ui` through native `minecraft:register`, then sends bounded discovery.
Each available host responds with a `Hello` in a fresh host-generated transport incarnation; the client replies through that same incarnation.
Paper and Velocity have separate typed endpoint identities and independent capability handshakes.
A proxy reissues backend discovery and renews its own transport incarnation after a server switch, so discarded transition packets cannot leave sequence gaps in either successor connection.
Stale-incarnation frames cannot enter a successor's protocol connection.
Proxy routing admits only authenticated client proxy actions and current-backend server frames.
It handles the native event and submits safe writes itself, preventing later asynchronous listeners from forwarding retired backend traffic.
The unreleased wire format remains protocol version 1; use matching Strata revisions until release.
Both sides require the same protocol version, intersect exact namespace/schema-version capabilities, and negotiate the minimum of each resource limit.
Registration freezes before a connection captures its capability snapshot.
No message supplies a class name, executable body, function, or server model.

`Snapshot` opens a complete session or resynchronizes its current revision, including typed presentation, category, visibility, input settings, HUD order, and the latest control sequence.
A connection routes one foreground Screen and multiple HUDs by stable session ID; switching does not allocate a new ID.
`Update` carries a base revision, replacement/removal records, and the next revision.
Every component and active modifier has a unique retained identity; repeated child references, missing children, cycles, excessive depth, and duplicate identities fail validation.
The client decodes registered properties and prepares state before replacing its declaration source.
A mismatched base revision requests `Resynchronize` without applying any patch prefix or replaying actions.

`Action` identifies the session, endpoint, exact event schema, and increasing operation sequence.
Each host binds reception to the actual sending player; callers cannot choose another player through a payload.
The server commits queued state before admitting a new action, checks current availability and the typed decoder, and executes through the shared core input boundary.
Duplicate sequences and retired endpoints never invoke handlers; gaps or invalid values fail the session.
`Acknowledgement` confirms processed operations and their resulting revision.
The client sends `Applied` only after validating and atomically installing a snapshot or update.
The server records its largest confirmed revision, ignores older duplicates, and rejects confirmations for future revisions or another session.
This confirms declaration installation rather than framebuffer presentation or human observation.
`Control` carries a server-sequenced presentation/input request, and `ControlApplied` confirms native application or a typed rejection.
Only the newest outstanding sequence may change the server handle's applied values; stale replies, snapshots, and replies after close cannot roll them back.
`ControlRequest` carries authenticated client intent for that same session, with its own monotonic sequence.
The owning server handle orders it with server-originated controls.
`ControlReceipt` completes the matching client intent after resulting authoritative controls, including duplicate and no-op intents, without allowing an older receipt to clear a newer request.
Rejected switches retain the applied presentation; terminal close supersedes controls.
`Close` carries a detached typed reason.

## Editing and local behavior

Bindings identify the server source, its replacement generation, the last accepted editing sequence, the authoritative value, and the current endpoint.
The client retains native control state by source identity and trusted type token.
It flushes pending edits before a business action and before accepting an incoming update.
An older acknowledgement does not replace a newer local draft; a server replacement generation supersedes pending edits.
Equal confirmed text does not assign the native editor again, preserving its retained editing state.

Input modifier projections declare their event variants, optional key/button filters, fixed propagation result, and typed server endpoints before input occurs.
The client installs only those subscriptions and checks their filters before sending an event.
The server validates the same variant and filter before invoking its current handler.
Changing a subscription policy retires its endpoints; already queued events for the old policy are acknowledged without calling a replacement handler.
Keyboard notifications preserve the physical key, scan code, and complete modifiers; pointer notifications preserve tree/local coordinates and fractional deltas; text notifications preserve committed Unicode scalars and IME composition details.
Fixed-button capture acquires and releases locally and sends gesture/cancellation notifications through separate endpoints.
An active gesture retains its original button policy until release or cancellation.
Callbacks that choose an `InputResult` or capture policy dynamically from an event still need a registered client implementation.
The client never waits for a server response to obtain a synchronous input result.
Stateful modifier registrations prepare shared native state outside declaration evaluation, alongside component preparation.

## Resources and bounds

Each routed fragment has an independent strictly increasing envelope sequence.
The receiver restores order across asynchronous proxy events using a queue bounded by bytes, entries, and gap timeout, and drains a bounded number per owner tick.
Duplicate sequences are ignored; conflicting queued duplicates fail validation.
The native packet bound includes the fixed envelope; negotiated fragment limits reserve its bytes.
The binary value codec uses explicit tags, big-endian numeric fields, strict UTF-8, and bounded byte/collection lengths.
Limits include frame/message bytes, aggregate values, structural depth, declaration count, queued bytes, fragment assembly time, and reconstruction time.
HUD capacity is negotiated per connection, with a default of 16.
Retained node and outgoing queue budgets cover all sessions on the connection.
Hidden HUDs continue receiving updates.
Extension decoders and factories must be nonblocking; work-budget checks surround trusted callbacks and traversal rather than interrupting a running callback.
Fragment assembly accepts one ordered message at a time and rejects interleaving, replayed fragments, invalid offsets, and expired assemblies.
Closing a session removes its unsent messages.
If a fragment group has already started, an ordered zero-length cancellation frame releases the peer's partial buffer before the typed close notification; it never delivers incomplete application data or interrupts another session's transfer.
Queue exhaustion is an explicit terminal failure, including for business operations.

Images transfer either resource identifiers or detached pixel bytes.
CPU Canvas projects the committed source revision and image; native Canvas references an installed renderer schema.
Tiled images retain only the standard bounded working set on the server, transfer its current ready/empty cells with a source generation, and reconstruct the standard tile renderer on the client.
Each client tile source replaces its complete current set; source replacement and terminal cleanup release pixels and subscribers.
Native Slot transactions additionally require the same captured client menu identity.
Container lifecycle changes retire affected Slot HUDs and ordinary remote Screens; unrelated HUDs retain their state.

## Extensions and ownership

An extension chooses a namespaced `ProjectionType` and positive schema version.
Its declaration projection supplies detached properties and typed `ProjectionAction` or `ProjectionBinding` endpoints.
`RemoteRegistry.element`, `modifier`, and `statefulModifier` register trusted decoders and client factories; custom semantics roles register an explicit role projection type.
Client preparation owns derived values in `RemoteClientStates`, keyed by remote identity plus a trusted `RemoteStateKey`.
Preparation prunes omitted entries; supplied release callbacks run on retirement and terminal cleanup, including partial preparation failures.
The client session also retains decoded factory captures for the current tree only.
Their keys are retained component/modifier identity, exact schema, and immutable wire properties; changed properties or retired identities replace them, and terminal close clears the tree.
This derived presentation cache preserves Canvas source identity across unrelated updates without caching authoritative server models or input values.

Registrations are optional for local-only components and modifiers.
A remote screen requiring an absent projection or unsupported schema fails explicitly before it can become a supported screen.
Plugin disable, disconnect, screen replacement, native container replacement, decoding failure, handler failure, and resource limits release the corresponding owner references and transfer queues.
Common lifecycle/source-cutoff rules remain in [UI sessions](../development/ui-sessions.md); runtime cache admission remains in [performance](../development/performance.md).
The shared host queues at most 64 lifecycle transitions requested during one input handler and drains them on its owner thread after that handler returns.
Replacement, close, disconnect, inventory changes, and plugin shutdown therefore cannot reenter an active core input operation; excess transition requests fail explicitly.
