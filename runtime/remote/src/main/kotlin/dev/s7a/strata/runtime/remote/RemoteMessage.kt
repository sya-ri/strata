package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiRejection
import java.util.Collections

/**
 * Typed messages carried over an already established player connection.
 * Session and sequence validation belongs to the connection/session owners, not the byte decoder.
 */
@OptIn(InternalStrataRuntimeApi::class)
public sealed interface RemoteMessage {
    /**
     * Terminal queue owner, or null for a connection-level greeting.
     */
    public val session: Long?

    /**
     * Negotiates the supported protocol and exact extension schemas before any screen is opened.
     */
    public class Hello(
        public val protocol: Int,
        public val limits: RemoteLimits,
        types: Set<ProjectionType>,
    ) : RemoteMessage {
        override val session: Long? get() = null
        public val types: Set<ProjectionType> = Collections.unmodifiableSet(types.toSet())
    }

    /**
     * Opens or resynchronizes one complete screen; title is an unresolved typed text projection.
     */
    public data class Snapshot(
        override val session: Long,
        public val revision: Long,
        public val title: ProjectionValue,
        public val tree: RemoteTree,
        public val pausesGame: Boolean = false,
        public val settings: RemoteUiSettings = RemoteUiSettings(),
        public val control: RuntimeUiControl? = null,
    ) : RemoteMessage

    /**
     * Applies one atomic difference only when the client's committed revision matches [baseRevision].
     */
    public data class Update(
        override val session: Long,
        public val baseRevision: Long,
        public val revision: Long,
        public val patch: RemotePatch,
    ) : RemoteMessage

    /**
     * Requests one registered typed action; the server authenticates its endpoint and sequence.
     */
    public data class Action(
        override val session: Long,
        public val sequence: Long,
        public val endpoint: Long,
        public val type: ProjectionType,
        public val value: ProjectionValue,
    ) : RemoteMessage

    /**
     * Confirms the largest processed action sequence and its authoritative resulting revision.
     */
    public data class Acknowledgement(
        override val session: Long,
        public val sequence: Long,
        public val revision: Long,
    ) : RemoteMessage

    /**
     * Confirms that the client validated and atomically installed a complete declaration revision.
     * This is not proof that a frame was presented or that an operator saw it.
     */
    public data class Applied(
        override val session: Long,
        public val revision: Long,
    ) : RemoteMessage

    /**
     * Server-ordered control request; declaration state and session identity remain unchanged.
     */
    public data class Control(
        override val session: Long,
        public val state: RuntimeUiControl,
    ) : RemoteMessage

    /**
     * Native presentation/input application result, independent of declaration and framebuffer acknowledgements.
     */
    public data class ControlApplied(
        override val session: Long,
        public val sequence: Long,
        public val rejection: UiRejection? = null,
    ) : RemoteMessage

    /**
     * Authenticated client intent, ordered independently and sequenced by the owning server session.
     */
    public data class ControlRequest(
        override val session: Long,
        public val state: RuntimeUiControl,
    ) : RemoteMessage

    /**
     * Confirms admission of a client intent after any resulting server control, including no-op requests.
     */
    public data class ControlReceipt(
        override val session: Long,
        public val sequence: Long,
        public val rejection: UiRejection? = null,
    ) : RemoteMessage

    /**
     * Requests a fresh snapshot without replaying any actions.
     */
    public data class Resynchronize(
        override val session: Long,
    ) : RemoteMessage

    /**
     * Ends one session, releasing both endpoints' retained references.
     */
    public data class Close(
        override val session: Long,
        public val reason: RemoteFailure,
    ) : RemoteMessage
}
