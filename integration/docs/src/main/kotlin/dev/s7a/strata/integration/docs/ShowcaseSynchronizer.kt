package dev.s7a.strata.integration.docs

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Synchronizes the generator-owned component assets, combined Markdown, and README region as one recoverable replacement.
 *
 * All source paths and replacement bytes are preflighted before sibling transaction files are created.
 * Recovery derives ownership from physical transaction paths, including when a delegate completes an operation before reporting a failure.
 * Recovery is attempted independently for each path, and a secondary recovery failure can retain a recoverable backup.
 * Synchronization runs synchronously on the caller's thread and requires exclusive access to its fixed sibling transaction paths until cleanup finishes.
 */
@Suppress("TooManyFunctions")
internal object ShowcaseSynchronizer {
    /**
     * Replaces the exact owned component assets, combined Markdown, and README region, attempting independent restoration of each original when replacement fails.
     *
     * @param launch validated repository, build, and staging paths.
     * @param output fully rendered and serialized output.
     * @param fileSystem filesystem boundary used for production operations and deterministic failure tests.
     * @throws IllegalArgumentException when an owned path, staged manifest, or README contract is invalid.
     * @throws Throwable when preparation, replacement, or successful-commit cleanup fails; independent recovery failures are suppressed on the original failure in operation order.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun synchronize(
        launch: ShowcaseLaunchArguments,
        output: ShowcaseOutput,
        fileSystem: ShowcaseFileSystem = NioShowcaseFileSystem,
    ) {
        val transaction = preflight(launch, output)
        try {
            prepare(transaction, fileSystem)
            replace(transaction, fileSystem)
        } catch (failure: Throwable) {
            rollback(transaction, fileSystem, failure)
            cleanupTemporary(transaction, fileSystem, failure)
            throw failure
        }
        cleanupCommitted(transaction, fileSystem)
    }

    private fun preflight(
        launch: ShowcaseLaunchArguments,
        output: ShowcaseOutput,
    ): Transaction {
        require(output.stagingRoot == launch.stagingRoot) { "Output staging does not match launcher staging." }
        val root = launch.projectRoot
        val docs = ShowcasePaths.contained(root, root.resolve("docs"), "documentation root")
        ShowcasePaths.requireDirectory(docs, "documentation root")
        val target = ShowcasePaths.contained(docs, docs.resolve("components"), "component documentation")
        val staged = ShowcasePaths.contained(output.stagingRoot, output.stagingRoot.resolve("components"), "component staging")
        validateDirectoryTree(target, "component documentation", required = false)
        validateDirectoryTree(staged, "component staging", required = true)
        require(fileSet(staged) == expectedFiles(output)) { "Staged component file set is not exact." }

        val markdown = ShowcasePaths.contained(docs, docs.resolve("components.md"), "component Markdown")
        val stagedMarkdown = ShowcasePaths.contained(output.stagingRoot, output.stagingRoot.resolve("components.md"), "component Markdown staging")
        validateRegularFile(markdown, "component Markdown", required = false)
        validateRegularFile(stagedMarkdown, "component Markdown staging", required = true)
        val updatedMarkdown = Files.readAllBytes(stagedMarkdown)

        val readme = root.resolve("README.md").toAbsolutePath().normalize()
        ShowcasePaths.requireSafeSegments(readme, "README")
        require(Files.isRegularFile(readme, LinkOption.NOFOLLOW_LINKS)) { "README is not a regular file: $readme" }
        val updatedReadme =
            ShowcaseReadme.replace(
                Files.readAllBytes(readme),
                output.rootReadmeRegion.toByteArray(StandardCharsets.UTF_8),
            )
        val transaction =
            Transaction(
                staged = staged,
                target = target,
                markdown = markdown,
                updatedMarkdown = updatedMarkdown,
                readme = readme,
                updatedReadme = updatedReadme,
                hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                hadMarkdown = Files.exists(markdown, LinkOption.NOFOLLOW_LINKS),
            )
        transaction.transientPaths().forEach { path ->
            ShowcasePaths.requireSafeSegments(path, "showcase transaction")
            require(Files.exists(path, LinkOption.NOFOLLOW_LINKS).not()) {
                "Showcase transaction path already exists: $path"
            }
        }
        return transaction
    }

    private fun prepare(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
    ) {
        fileSystem.copy(transaction.staged, transaction.nextTarget)
        fileSystem.write(transaction.nextMarkdown, transaction.updatedMarkdown)
        fileSystem.write(transaction.nextReadme, transaction.updatedReadme)
    }

    private fun replace(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
    ) {
        if (transaction.hadTarget) {
            fileSystem.move(transaction.target, transaction.backupTarget)
        }
        fileSystem.move(transaction.nextTarget, transaction.target)
        if (transaction.hadMarkdown) {
            fileSystem.move(transaction.markdown, transaction.backupMarkdown)
        }
        fileSystem.move(transaction.nextMarkdown, transaction.markdown)
        fileSystem.move(transaction.readme, transaction.backupReadme)
        fileSystem.move(transaction.nextReadme, transaction.readme)
    }

    private fun rollback(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
        primary: Throwable,
    ) {
        rollbackReadme(transaction, fileSystem, primary)
        rollbackMarkdown(transaction, fileSystem, primary)
        rollbackTarget(transaction, fileSystem, primary)
    }

    private fun rollbackMarkdown(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
        primary: Throwable,
    ) {
        if (exists(transaction.backupMarkdown)) {
            attempt(primary) {
                if (exists(transaction.markdown)) fileSystem.delete(transaction.markdown)
            }
            attempt(primary) {
                if (exists(transaction.backupMarkdown)) fileSystem.move(transaction.backupMarkdown, transaction.markdown)
            }
        } else if (transaction.hadMarkdown.not() && exists(transaction.markdown)) {
            attempt(primary) { fileSystem.delete(transaction.markdown) }
        }
    }

    private fun rollbackReadme(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
        primary: Throwable,
    ) {
        if (exists(transaction.backupReadme)) {
            attempt(primary) {
                if (exists(transaction.readme)) fileSystem.delete(transaction.readme)
            }
            attempt(primary) {
                if (exists(transaction.backupReadme)) fileSystem.move(transaction.backupReadme, transaction.readme)
            }
        }
    }

    private fun rollbackTarget(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
        primary: Throwable,
    ) {
        if (exists(transaction.backupTarget)) {
            attempt(primary) {
                if (exists(transaction.target)) fileSystem.deleteTree(transaction.target)
            }
            attempt(primary) {
                if (exists(transaction.backupTarget)) fileSystem.move(transaction.backupTarget, transaction.target)
            }
        } else if (transaction.hadTarget.not() && exists(transaction.target)) {
            attempt(primary) { fileSystem.deleteTree(transaction.target) }
        }
    }

    private fun cleanupTemporary(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
        primary: Throwable,
    ) {
        attempt(primary) {
            if (exists(transaction.nextTarget)) fileSystem.deleteTree(transaction.nextTarget)
        }
        attempt(primary) {
            if (exists(transaction.nextMarkdown)) fileSystem.delete(transaction.nextMarkdown)
        }
        attempt(primary) {
            if (exists(transaction.nextReadme)) fileSystem.delete(transaction.nextReadme)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun cleanupCommitted(
        transaction: Transaction,
        fileSystem: ShowcaseFileSystem,
    ) {
        var cleanupFailure: Throwable? = null

        fun cleanup(operation: () -> Unit) {
            try {
                operation()
            } catch (failure: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure
                } else {
                    addSuppressed(cleanupFailure, failure)
                }
            }
        }
        cleanup {
            if (exists(transaction.nextTarget)) fileSystem.deleteTree(transaction.nextTarget)
        }
        cleanup {
            if (exists(transaction.nextMarkdown)) fileSystem.delete(transaction.nextMarkdown)
        }
        cleanup {
            if (exists(transaction.nextReadme)) fileSystem.delete(transaction.nextReadme)
        }
        cleanup {
            if (exists(transaction.backupTarget)) fileSystem.deleteTree(transaction.backupTarget)
        }
        cleanup {
            if (exists(transaction.backupMarkdown)) fileSystem.delete(transaction.backupMarkdown)
        }
        cleanup {
            if (exists(transaction.backupReadme)) fileSystem.delete(transaction.backupReadme)
        }
        val failure = cleanupFailure
        if (failure != null) throw failure
    }

    @Suppress("TooGenericExceptionCaught")
    private fun attempt(
        primary: Throwable,
        operation: () -> Unit,
    ) {
        try {
            operation()
        } catch (failure: Throwable) {
            addSuppressed(primary, failure)
        }
    }

    private fun addSuppressed(
        primary: Throwable,
        failure: Throwable,
    ) {
        if (canSuppress(primary, failure).not()) return
        primary.addSuppressed(failure)
    }

    private fun canSuppress(
        primary: Throwable,
        failure: Throwable,
    ): Boolean {
        if (primary === failure) return false
        if (graphsOverlap(primary, failure)) return false
        return primary.suppressed.none { existing -> graphsOverlap(existing, failure) }
    }

    private fun graphsOverlap(
        first: Throwable,
        second: Throwable,
    ): Boolean {
        if (containsIdentity(first, second) || containsIdentity(second, first)) return true
        val firstIdentities = identities(first)
        return identities(second).any { identity -> firstIdentities.contains(identity) }
    }

    private fun containsIdentity(
        root: Throwable,
        candidate: Throwable,
    ): Boolean {
        val pending = ArrayDeque<Throwable>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current === candidate) return true
            if (visited.add(current)) {
                current.cause?.let(pending::addLast)
                current.suppressed.forEach(pending::addLast)
            }
        }
        return false
    }

    private fun identities(root: Throwable): Set<Throwable> {
        val identities = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (identities.add(current)) {
                current.cause?.let(pending::addLast)
                current.suppressed.forEach(pending::addLast)
            }
        }
        return identities
    }

    private fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun expectedFiles(output: ShowcaseOutput): Set<String> =
        (
            output.sections.map { section -> "${section.slug}.png" } +
                listOf("overview.png", "minecraft-26.2-parity.properties")
        ).toSortedSet()

    private fun fileSet(root: Path): Set<String> =
        Files.walk(root).use { stream ->
            stream
                .filter { path -> path != root && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .map { path -> root.relativize(path).toString().replace('\\', '/') }
                .toList()
                .toSortedSet()
        }

    private fun validateDirectoryTree(
        root: Path,
        label: String,
        required: Boolean,
    ) {
        ShowcasePaths.requireSafeSegments(root, label)
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS).not()) {
            require(required.not()) { "$label is missing: $root" }
            return
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "$label is not a directory: $root" }
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                ShowcasePaths.requireSafeSegments(path, label)
                require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "$label contains a non-regular entry: $path"
                }
            }
        }
    }

    private fun validateRegularFile(
        path: Path,
        label: String,
        required: Boolean,
    ) {
        ShowcasePaths.requireSafeSegments(path, label)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS).not()) {
            require(required.not()) { "$label is missing: $path" }
            return
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "$label is not a regular file: $path" }
    }

    private class Transaction(
        val staged: Path,
        val target: Path,
        val markdown: Path,
        val updatedMarkdown: ByteArray,
        val readme: Path,
        val updatedReadme: ByteArray,
        val hadTarget: Boolean,
        val hadMarkdown: Boolean,
    ) {
        val nextTarget: Path = requireNotNull(target.parent).resolve(".strata-components-next")
        val backupTarget: Path = requireNotNull(target.parent).resolve(".strata-components-backup")
        val nextMarkdown: Path = requireNotNull(markdown.parent).resolve(".strata-components-markdown-next")
        val backupMarkdown: Path = requireNotNull(markdown.parent).resolve(".strata-components-markdown-backup")
        val nextReadme: Path = requireNotNull(readme.parent).resolve(".strata-readme-next")
        val backupReadme: Path = requireNotNull(readme.parent).resolve(".strata-readme-backup")

        fun transientPaths(): List<Path> = listOf(nextTarget, backupTarget, nextMarkdown, backupMarkdown, nextReadme, backupReadme)
    }
}
