package dev.s7a.strata.runtime.headless

import java.util.ArrayList
import java.util.Collections

/**
 * Creates a detached, unmodifiable snapshot of a caller-owned list.
 *
 * The returned list retains no reference to the supplied list and has stable order and elements after this call.
 * Reads are thread-safe when the elements themselves are immutable.
 * The caller must not mutate [values] concurrently while this snapshot is being created.
 *
 * @param values the caller-owned source list.
 * @return an unmodifiable defensive copy in source order.
 */
@JvmSynthetic
internal fun <T> immutableSnapshot(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
