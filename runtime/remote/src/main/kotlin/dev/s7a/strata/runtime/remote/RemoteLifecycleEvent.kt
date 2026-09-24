package dev.s7a.strata.runtime.remote

import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession

/**
 * Committed lifecycle notifications emitted on the service owner thread.
 * Adapters translate these into their platform event API; listener failures never undo committed state.
 * UI identity is scoped to the service and connection; application event fields are detached snapshots.
 */
public sealed interface RemoteLifecycleEvent<out Owner> {
    /**
     * Successful negotiation, once per connection generation.
     */
    public data class Ready(
        public val capabilities: UiClientCapabilities,
    ) : RemoteLifecycleEvent<Nothing>

    /**
     * Retirement of a previously ready connection after every UI has closed.
     */
    public data class Disconnected(
        public val reason: UiCloseReason,
    ) : RemoteLifecycleEvent<Nothing>

    /**
     * First acknowledged native presentation, including initially hidden HUDs.
     */
    public data class Opened<Owner>(
        public val owner: Owner,
        public val identity: Long,
        public val session: UiSession,
        public val presentation: UiPresentation,
        public val category: UiCategory?,
    ) : RemoteLifecycleEvent<Owner>

    /**
     * Acknowledged transition between distinct presentations of one retained session.
     */
    public data class PresentationChanged<Owner>(
        public val owner: Owner,
        public val identity: Long,
        public val session: UiSession,
        public val previous: UiPresentation,
        public val presentation: UiPresentation,
        public val category: UiCategory?,
    ) : RemoteLifecycleEvent<Owner>

    /**
     * Terminal ownership, including rejected openings that never emitted [Opened].
     */
    public data class Closed<Owner>(
        public val owner: Owner,
        public val identity: Long,
        public val session: UiSession,
        public val presentation: UiPresentation?,
        public val category: UiCategory?,
        public val reason: UiCloseReason,
    ) : RemoteLifecycleEvent<Owner>
}
