package dev.s7a.strata.integration.docs

import java.nio.file.Path

/**
 * Narrow filesystem boundary for the showcase synchronization transaction.
 *
 * Implementations own the individual filesystem operations used while preparing, replacing, recovering, and cleaning a transaction.
 * The boundary lets tests model failures before or after a delegate operation without touching checked documentation.
 * Calls are synchronous on the transaction caller's thread, and the boundary does not retain paths after an operation returns.
 */
internal interface ShowcaseFileSystem {
    /**
     * Copies a regular, symlink-free directory tree using file contents only.
     *
     * @param source existing staged directory.
     * @param target absent destination directory.
     * @throws Throwable when copying fails; the destination may be partial.
     */
    fun copy(
        source: Path,
        target: Path,
    )

    /**
     * Writes bytes to an absent regular file.
     *
     * @param path destination file.
     * @param bytes exact file contents consumed synchronously without retaining or mutating the array.
     * @throws Throwable when writing fails; the destination may be partial.
     */
    fun write(
        path: Path,
        bytes: ByteArray,
    )

    /**
     * Moves one preflighted path to another path on the same filesystem.
     *
     * @param source source path.
     * @param target absent destination path.
     * @throws Throwable when moving fails; the physical source and destination state is authoritative.
     */
    fun move(
        source: Path,
        target: Path,
    )

    /**
     * Deletes one regular file when it exists.
     *
     * @param path file path.
     * @throws Throwable when deletion fails; the file may remain.
     */
    fun delete(path: Path)

    /**
     * Deletes one symlink-free directory tree when it exists.
     *
     * @param path directory path.
     * @throws Throwable when deletion fails; part of the tree may remain.
     */
    fun deleteTree(path: Path)
}
