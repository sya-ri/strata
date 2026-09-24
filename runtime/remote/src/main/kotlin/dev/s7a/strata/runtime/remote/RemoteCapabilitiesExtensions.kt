package dev.s7a.strata.runtime.remote

import dev.s7a.strata.ui.UiClientCapabilities

/**
 * Copies negotiated application support without exposing wire framing or runtime implementation types.
 */
public fun RemoteCapabilities.toUiCapabilities(): UiClientCapabilities = UiClientCapabilities(types, limits.hudSessions)
