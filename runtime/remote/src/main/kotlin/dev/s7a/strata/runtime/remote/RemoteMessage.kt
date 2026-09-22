package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import java.util.Collections

/**
 * Typed messages carried over an already established player connection.
 * Session and sequence validation belongs to the connection/session owners, not the byte decoder.
 */
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
