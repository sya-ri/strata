package dev.s7a.strata.runtime.remote

/**
 * Authenticated host of a remote screen on one native player connection.
 * Proxies must never forward backend frames claiming the proxy endpoint.
 */
public enum class RemoteEndpoint {
    Server,
    Proxy,
}
