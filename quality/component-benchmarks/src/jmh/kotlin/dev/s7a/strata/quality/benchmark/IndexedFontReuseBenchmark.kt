package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.minecraft.font.MinecraftIndexedFontAssetSource
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Unchanged source controls: construction and validation occur only in trial setup.
 */
public open class IndexedFontReuseBenchmark {
    /**
     * Borrows captured paths without reading or validating the index again.
     */
    @Benchmark
    public fun reusedPaths(state: Index): Set<String> = checkNotNull(state.source).paths()

    /**
     * Reads fresh object bytes from a previously constructed source.
     */
    @Benchmark
    public fun reusedRead(state: Index): ByteArray? = checkNotNull(state.source).read(IndexedFontAssetFiles.defaultPath)

    /**
     * Owns one reusable source until trial teardown; no previous trial or output bytes are retained.
     */
    @State(Scope.Thread)
    public open class Index {
        /**
         * Both frozen alias shapes at one and 4,096 records.
         */
        @JvmField
        @Param("Distinct1", "Shared1", "Distinct4096", "Shared4096")
        public var input: IndexedFontInput = IndexedFontInput.Distinct1

        /**
         * The current trial's owner, cleared at teardown.
         */
        public var source: MinecraftIndexedFontAssetSource? = null

        /**
         * Constructs the indexed source before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            IndexedFontAssetFiles.open(input).use { files -> source = files.source() }
        }

        /**
         * Releases the captured map and object-root reference.
         */
        @TearDown(Level.Trial)
        public fun close() {
            source = null
        }
    }

    /**
     * Verifies the reused-source matrix without introducing a specialized Gradle task.
     */
    public companion object {
        /**
         * Requires eight unchanged cases and zero constructor work after setup.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(IndexedFontReuseBenchmark::class.java), setOf("avgt")).size == 8)
            IndexedFontAssetControls.verifyReuse()
        }
    }
}
