package dev.s7a.strata.runtime.remote

import java.util.UUID

/**
 * Host-generated transport incarnation, independent of screen and operation identities.
 * A newly joined backend chooses a fresh identity; frames for its predecessor cannot enter its protocol connection.
 */
public data class RemoteAddress(
    public val endpoint: RemoteEndpoint,
    public val incarnation: UUID = UUID.randomUUID(),
)
