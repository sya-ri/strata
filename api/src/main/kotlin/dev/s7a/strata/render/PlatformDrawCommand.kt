package dev.s7a.strata.render

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable opaque draw payload shared only between a platform element and its matching runtime adapter.
 *
 * Core retains the payload by reference and preserves its position in the ordered display list without inspecting it.
 * Implementations must be immutable snapshots that are safe to retain after the originating paint callback returns.
 * A backend that does not recognize an implementation must fail before producing partial output.
 */
@InternalStrataRuntimeApi
public interface PlatformDrawCommand
