package dev.s7a.strata.runtime.minecraft.font

/**
 * Callback-scoped entry observer whose budget and owner references are released when enumeration ends.
 * Foreign-thread, concurrent, and retained late calls fail before reading or mutating loader counters.
 *
 * @param budget loader-owned counters borrowed only until [close].
 * @param allowance inclusive entry allowance selected before enumeration.
 */
internal class FontEntryObserver(
    budget: FontLoadBudget,
    private val allowance: Long,
) : () -> Unit,
    AutoCloseable {
    @Volatile
    private var owner: Thread? = Thread.currentThread()
    private var budget: FontLoadBudget? = budget

    /**
     * Entries observed synchronously during the current enumeration, including a detection entry.
     */
    var observed: Long = 0
        private set

    override fun invoke() {
        val current = requireActive()
        observed++
        current.claim(FontLoadBudget.Kind.SourceEntries, 1)
        requireFontLimit(observed, allowance, "source entries")
    }

    /**
     * Charges returned paths that an old or custom source did not report individually.
     *
     * @param count complete returned path count, checked before loader copying.
     * @throws IllegalStateException outside this observer's synchronous owner-thread lifetime.
     * @throws MinecraftFontLoadLimitException when aggregate capacity is exhausted.
     */
    fun complete(count: Int) {
        val current = requireActive()
        if (observed < count) {
            val unreported = count - observed
            observed = count.toLong()
            current.claim(FontLoadBudget.Kind.SourceEntries, unreported)
        }
    }

    /**
     * Releases the borrowed loader and thread references, making every retained callback invocation invalid.
     * The loader calls this exactly once in its enumeration finally block.
     */
    override fun close() {
        requireActive()
        budget = null
        owner = null
    }

    private fun requireActive(): FontLoadBudget {
        check(Thread.currentThread() === owner) { "Font entry callbacks require the active enumeration thread." }
        return checkNotNull(budget) { "Font entry enumeration has ended." }
    }
}
