package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element

/**
 * Owns one session content lambda until the session releases it.
 *
 * The owner evaluates and releases this object on the session owner thread.
 * Release is idempotent, clears the lambda before returning, and never invokes user cleanup work.
 *
 * @param content the owner-thread content evaluator.
 */
internal class SessionContent(
    content: () -> Element,
) {
    private var retainedContent: (() -> Element)? = content

    /**
     * Evaluates the retained content on the owner thread.
     *
     * @return the complete element description.
     * @throws IllegalStateException when this owner has already been released.
     * @throws Throwable when the retained content evaluator fails.
     */
    internal fun evaluate(): Element = checkNotNull(retainedContent) { "Session content has already been released." }.invoke()

    /**
     * Releases the retained content exactly once.
     *
     * Repeated owner-thread calls are no-ops.
     */
    internal fun release() {
        if (retainedContent == null) {
            return
        }
        retainedContent = null
    }
}
