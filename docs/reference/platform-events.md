# Paper, Folia, and Velocity lifecycle events

The platform API modules publish lifecycle notifications without exposing the transport implementation.
Paper event classes live in `dev.s7a.strata.paper.event`; Velocity classes live in `dev.s7a.strata.velocity.event`.
Both platform APIs provide the following notifications.

| Event | Committed transition | Additional data |
| --- | --- | --- |
| `StrataClientReadyEvent` | A connection completes successful capability negotiation. | Negotiated `UiClientCapabilities`, including types and HUD limit. |
| `StrataClientDisconnectedEvent` | A previously ready connection ends. | Terminal reason. |
| `StrataUiOpenedEvent` | The client applies the session's first native presentation. | Owning plugin, stable identity, session, applied presentation, optional category. |
| `StrataUiPresentationChangedEvent` | The client applies a different presentation. | The same ownership fields, previous and applied presentation. |
| `StrataUiClosedEvent` | Session ownership ends, including an unsuccessful opening. | Ownership fields, last applied presentation or null, category, terminal reason. |

Every event identifies the authenticated player associated with that connection.
UI notifications describe sessions owned by the emitting platform: Velocity does not emit lifecycle events for a Paper plugin's backend-owned UI.
These events are notifications, cannot be cancelled, and do not replace synchronous component input callbacks.

## Application and terminal boundaries

Opening an API handle does not emit `StrataUiOpenedEvent` until the client confirms native application.
This acknowledgement confirms the native presentation change, not framebuffer pixels or human observation.
Rejected and superseded switches do not produce a presentation-change event.
Duplicate acknowledgements and resynchronization do not repeat opening or roll back the last applied presentation.

Closing emits one terminal UI event, including when a definition transfers successfully but cannot be displayed.
Such a failed opening has null presentation and no preceding opened event.
Closure is idempotent; a closed event's session cannot be revived.
Connection shutdown dispatches its owned UI closures before the disconnected notification.
Readiness and disconnection pair within one successful connection generation; reconnecting or changing the backend can establish another generation for the same player.

Opening and closing another UI from a listener follow the same deferred lifecycle rules as UI callbacks.
State is committed before notification, and listener failure is reported without rolling the transition back or retaining terminated ownership.

## Threads and dependency boundaries

Paper and Folia use ordinary Bukkit events with a separate handler list for each event class.
Paper delivers them on the primary server thread; Folia ordinarily delivers them on the player's entity region inside that player's UI execution owner.
A Folia region migration preserves UI ownership without preserving a physical thread.
Terminal notifications can occur during entity retirement or shutdown, where native world access is restricted; read event snapshots and do not assume the callback grants region ownership.
External Folia state or session access must first reach the player's region and then enter `PaperUi.execute(player) { ... }`.
Compile against `strata-paper-api`, declare the installed Strata plugin dependency, and register normal Bukkit listeners.

Velocity submits ordinary event objects through its event manager without waiting for listeners on the UI worker.
The order above describes submission order; asynchronous listener completion can overlap and must not be used as an ordering barrier.
Player, owner, identity, presentation, category, capabilities, and reason describe the notification's snapshot.
The `session` is a live execution-owner-confined handle: queue its property reads and operations through `VelocityUi.execute(ownerPlugin) { ... }`.
Never block the UI worker waiting for an event listener or a returned future.
Compile against `strata-velocity-api` and declare the installed `strata` plugin dependency.

The [Paper guide](../guides/paper.md), [Velocity guide](../guides/velocity.md), and their compiled plugins show installation and public API use.
The [remote protocol](remote-protocol.md) owns negotiation and acknowledgement details; [screens and state](../guides/screens-and-state.md) owns session controls and event-receiver lifetime.
