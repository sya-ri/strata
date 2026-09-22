package dev.s7a.strata.runtime.remote

/**
 * A terminal protocol failure carrying the reason exposed to both session owners.
 */
public class RemoteProtocolException(
    public val reason: RemoteFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
