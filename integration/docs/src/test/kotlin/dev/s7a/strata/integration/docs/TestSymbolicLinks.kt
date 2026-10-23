package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates real fixture links, reporting filesystem capability failures as skipped tests.
 */
internal object TestSymbolicLinks {
    /**
     * Requires valid fixture paths and propagates unexpected setup failures instead of silently omitting assertions.
     */
    fun create(
        link: Path,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: FileSystemException) {
            val unavailableReasons =
                setOf(
                    "A required privilege is not held by the client.",
                    "Operation not permitted",
                    "Operation not supported",
                    "Function not implemented",
                    "\u30af\u30e9\u30a4\u30a2\u30f3\u30c8\u306f\u8981\u6c42\u3055\u308c\u305f\u7279\u6a29\u3092\u4fdd\u6709\u3057\u3066\u3044\u307e\u305b\u3093\u3002",
                )
            if (failure !is AccessDeniedException && failure.reason !in unavailableReasons) throw failure
            assumeTrue(false, "Symbolic links unavailable: $failure")
        } catch (failure: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links unsupported: $failure")
        }
    }
}
