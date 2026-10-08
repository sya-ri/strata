# Declaration projection

Declaration projection describes a retained screen as detached typed properties and server-owned action endpoints.
It is an optional extension of the [Element SPI](element-spi.md) and [Modifier SPI](modifier-spi.md); local primitives and modifiers need no registration or projection implementation.
The public SPI does not inspect concrete component classes.

## Descriptions and retained resources

An `Element` or `ModifierElement` can provide a `DeclarationProjection<P>` with an exact `ProjectionType`, immutable local properties, and an encoder.
`ProjectionType` combines a namespaced resource identifier with a positive schema version.
Both endpoints must agree on that exact pair; changing a record or event schema requires a new version.
`ProjectionValue` permits absent values, booleans, integers, finite reals, text, defensive byte snapshots, and ordered sequences.
Application models, functions, class names, native handles, and executable code are not wire values.
`ProjectionValue.Bytes` snapshots ordinary constructor inputs and returns a detached mutable array from `toByteArray`.
The `InternalStrataRuntimeApi` bridge permits copying into caller-owned output storage and transferring a fresh, complete, exact payload array.
A transferring runtime must validate the payload first, relinquish every mutable alias before publication, and never transfer pooled, shared, or oversized backing storage.
These operations expose no mutable view or retained callback; independent readers share only the immutable value.

An attachment-owned resource can instead implement `DeclarationProjectionNode` on its component or modifier node.
The runtime reads the retained projection after the shared source cutoff, giving it precedence over the immutable description's projection.
`prepareDeclaration` may publish geometry implied by explicit viewport properties before dynamic rows are materialized.
It must not measure, draw, mutate session state, or invoke business handlers.
CPU Canvas uses its existing committed source binding; virtual lists use their existing bounded row generation and keyed reconciliation.
No second resource subscription or alternate row model is created by the projection SPI.

## Fixed detached values

The opt-in runtime bridge `DeclarationProjection.fixed` accepts one immutable detached value and never invokes a caller encoder or scope operation.
Its `fixedValue` is the complete constant output; ordinary constructors expose no fixed-value proof, even after equal results.
Standard positional property projections use this contract, while action, binding, image, Canvas, tile and other arbitrary encoders retain their ordinary execution behavior.
Standard layout and modifier descriptions keep their existing projection getters, so this reuse adds no projection creation during local-only description construction.

Core applies the source cutoff, `prepareDeclaration` and dynamic-child reconciliation before selecting a declaration snapshot.
It reuses a subtree only with the exact current description identities, equal fixed type/enabled/value inputs and unchanged ordered modifier and child snapshots.
Only fixed subtrees occupy the cache: one current snapshot per retained component, sharing descendant snapshots without keeping a transport history.
Replacement or removal invalidates ancestor snapshots before node callbacks; session detachment and terminal cleanup clear every current key before resource callbacks.
This cache is confined to its owning retained tree's execution owner, and stores no authoritative source snapshots or registered actions.

The owner-tree `RuntimeDeclaration.revision` is separate from retained node identity and the adapter's wire revision.
An arbitrary encoder or changed subtree always advances that projection revision; equal opaque wire output still executes its encoder and refreshes current endpoints.
A remote session may reuse its validated tree for an unchanged complete fixed root revision under the same session, supported types and negotiated limits.
The server retains the primitive revision and its existing detached tree, without retaining a runtime declaration or local callback as a key.
Preparation, source commitment, UI controls, acknowledgements and cleanup continue through the ordinary session boundary.
Fixed metadata construction and equality are still work and belong in performance measurements.

## Scoped actions and bindings

The encoder receives a callback-lifetime `ProjectionScope`.
It can register a `ProjectionAction<T>`, project a `ProjectionBinding<T>`, encode unresolved text or immutable CPU images, and require a nested client capability.
The scope must not escape or mutate authoritative state.
Runtime adapters validate negotiated bounds and types before transmitting a complete declaration.

An action decoder validates external input before its typed handler can execute.
The adapter associates endpoints with the authenticated connection, current retained owner, and declared action position.
`inputEnabled` disables the declaration's own actions and its subtree's actions; it is checked again against the server's current declaration before dispatch.
Synchronous input propagation, focus, and pointer capture remain client behavior and require a client-installed contract when they are application-specific.

Bindings identify caller-owned state by referential identity, which is never serialized.
The remote adapter owns source IDs, replacement generations, and edit acknowledgements while the authoritative value stays in the binding.
Replacing a source retires its old ID; changing its value outside an accepted edit advances its replacement generation.
Client controls keep unacknowledged newer edits when older confirmations arrive and avoid assigning unchanged confirmed values to native editors.
An explicit newer replacement generation overrides pending local edits.

`SemanticsRole.projectionType` optionally identifies an immutable accessibility role installed by a client extension.
The declaration requires that nested capability before the screen can open.
Local application roles may leave it absent.

## Session ownership

Projection runs through the core's existing retained session and source cutoff, described in [UI sessions](../development/ui-sessions.md).
`RuntimeDeclaration` is a callback-local snapshot containing application descriptions and must never be serialized as an object graph or retained as a transport history.
Its positive node and modifier IDs survive compatible reconciliation and are never reused by that tree.
Only the encoder's detached result belongs to the transport.

The adapter performs authentication, sequence and generation checks, input decoding, and capability admission before calling the core's `dispatchAction` input boundary.
A projection or handler failure follows ordinary session failure and cleanup behavior, releasing subscriptions, handlers, and resource bindings.
Removing descriptions retires their action endpoints; terminal close releases the complete current projection and pending transfer ownership.
