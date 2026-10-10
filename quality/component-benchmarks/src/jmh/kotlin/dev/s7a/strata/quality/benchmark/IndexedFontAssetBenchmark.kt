package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Times actual indexed-source acquisition, including file reading, JSON parsing and boundary validation.
 * File/JSON/archive preparation and qualification remain outside all three measured operations.
 */
public open class IndexedFontAssetBenchmark {
    /**
     * Constructs the real source and returns its observable path set to JMH.
     */
    @Benchmark
    public fun constructPaths(state: Index): Set<String> = state.files.source().paths()

    /**
     * Includes construction and exactly one default-font read; the empty input returns null.
     */
    @Benchmark
    public fun constructRead(state: Index): ByteArray? = state.files.source().read(IndexedFontAssetFiles.defaultPath)

    /**
     * Executes the shipped indexed/archive/directory example and consumes detached font output.
     */
    @Benchmark
    public fun loadExample(state: Index): IndexedFontAssetFiles.Output = state.files.output(state.files.loadExample())

    /**
     * Borrows immutable common files for one trial and retains no source between measured operations.
     */
    @State(Scope.Thread)
    public open class Index {
        /**
         * The nine frozen nonduplicated input cases.
         */
        @JvmField
        @Param
        public var input: IndexedFontInput = IndexedFontInput.Empty

        /**
         * Stable filesystem input owned by this trial rather than a retained constructed source.
         */
        public lateinit var files: IndexedFontAssetFiles

        /**
         * Resolves prebuilt common inputs before JMH begins sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            files = IndexedFontAssetFiles.open(input)
        }

        /**
         * Releases trial-owned references without deleting shared frozen inputs.
         */
        @TearDown(Level.Trial)
        public fun close() {
            files.close()
        }
    }

    /**
     * Exposes deterministic controls to the existing generic fixture discovery path.
     */
    public companion object {
        /**
         * Requires all 27 cold cases and the complete independently expected boundary corpus.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(IndexedFontAssetBenchmark::class.java), setOf("avgt")).size == 27)
            IndexedFontAssetControls.verify()
        }
    }
}
