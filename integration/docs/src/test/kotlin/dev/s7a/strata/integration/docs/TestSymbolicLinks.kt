package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
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
            if (failure is FileAlreadyExistsException || failure is NoSuchFileException || failure is NotDirectoryException) throw failure
            assumeTrue(false, "Symbolic links unavailable: $failure")
        } catch (failure: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links unsupported: $failure")
        }
    }
}
