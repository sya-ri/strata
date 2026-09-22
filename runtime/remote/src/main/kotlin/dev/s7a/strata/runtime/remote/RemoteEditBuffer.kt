package dev.s7a.strata.runtime.remote

/**
 * Owner-thread optimistic editing state independent of any particular text or form control.
 * Older acknowledgements cannot overwrite a newer local edit; a newer authoritative generation resets pending edits.
 */
public class RemoteEditBuffer<T : Any>(
    initial: T,
    generation: Long = 1,
) {
    public var value: T = initial
        private set
    public var generation: Long = generation
        private set
    private var lastEdit: Long = 0
    private var lastAcknowledged: Long = 0

    init {
        require(0 < generation) { "Editing generations must be positive." }
    }

    /**
     * Records a local edit after its action sequence has been allocated.
     */
    public fun edit(
        value: T,
        sequence: Long,
    ) {
        require(lastEdit < sequence) { "Editing sequences must increase." }
        this.value = value
        lastEdit = sequence
    }

    /**
     * Applies an authoritative value only when no newer local edit would be lost.
     * Returns whether the visible value changed; callers preserve selection and IME on a false result.
     */
    public fun reconcile(
        value: T,
        generation: Long,
        acknowledgedSequence: Long,
    ): Boolean {
        require(0 < generation && 0 <= acknowledgedSequence) { "Invalid editing confirmation." }
        if (generation < this.generation) return false
        if (this.generation < generation) {
            this.generation = generation
            lastEdit = 0
            lastAcknowledged = 0
        } else if (acknowledgedSequence < lastAcknowledged || acknowledgedSequence < lastEdit) {
            return false
        }
        lastAcknowledged = acknowledgedSequence
        val changed = this.value != value
        this.value = value
        return changed
    }
}
