package dev.s7a.strata.runtime.minecraft

import java.lang.reflect.Field

/**
 * Counts actual candidate and prepared-bounds reads while painting the original run on the test thread.
 * Binary-search reads are excluded, and closing restores the exact original immutable lists.
 */
internal class MinecraftTextReadProbe(
    runs: List<MinecraftTextRun>,
) : AutoCloseable {
    private val storage = mutableListOf<Storage>()
    private val walker = StackWalker.getInstance()

    /**
     * Actual candidate reads observed after installation, including separate shadow passes.
     */
    internal var visits: Int = 0
        private set

    init {
        try {
            for (run in runs.distinct()) {
                for (name in listOf("glyphs", "sampledGlyphs")) {
                    val field = MinecraftTextRun::class.java.getDeclaredField(name)
                    check(field.trySetAccessible())
                    val original = field.get(run) as List<*>
                    field.set(run, ReadList(original, ::observe))
                    storage.add(Storage(run, field, original))
                }
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun observe() {
        val owner =
            walker.walk { frames ->
                frames
                    .filter { frame -> frame.className == MinecraftTextRun::class.java.name }
                    .findFirst()
                    .map { frame -> frame.methodName.substringBefore('$') }
                    .orElse("")
            }
        if (owner in setOf(MinecraftTextRun::paintVisible.name, "hasPreparedTextBounds")) visits++
    }

    override fun close() {
        for ((run, field, original) in storage.asReversed()) field.set(run, original)
        storage.clear()
    }

    /**
     * One test-owned replacement whose original value must be restored.
     */
    private data class Storage(
        val run: MinecraftTextRun,
        val field: Field,
        val original: List<*>,
    )

    /**
     * Reads the original immutable data while keeping all observation in the test artifact.
     */
    private class ReadList(
        private val original: List<*>,
        private val observe: () -> Unit,
    ) : AbstractList<Any?>() {
        override val size: Int get() = original.size

        override fun get(index: Int): Any? {
            observe()
            return original[index]
        }
    }
}
