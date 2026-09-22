package dev.s7a.strata.runtime.remote

import kotlin.reflect.KClass

/**
 * Stable referential type token for one client extension's retained presentation state.
 * Keep a singleton token per logical state kind; server data never selects a JVM class.
 */
public class RemoteStateKey<T : Any>(
    internal val type: KClass<T>,
)
