package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Verifies source-safe staging, checking, and clean-tree synchronization in temporary repositories.
 */
internal class ShowcaseStorageSynchronizerTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun cleanSynchronizationAndCheckPreserveSourceBytes() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        ShowcaseStorage.writeStaging(output)
        val beforeReadme = Files.readAllBytes(temporaryRoot.resolve("README.md"))

        ShowcaseSynchronizer.synchronize(launch, output)
        val afterSyncReadme = Files.readAllBytes(temporaryRoot.resolve("README.md"))
        val afterSyncComponents = snapshot(temporaryRoot.resolve("docs/components"))
        ShowcaseStorage.checkSource(temporaryRoot, output)

        assertTrue(afterSyncReadme.contentEquals(beforeReadme).not())
        assertArrayEquals(afterSyncReadme, Files.readAllBytes(temporaryRoot.resolve("README.md")))
        assertEqualsSnapshot(afterSyncComponents, snapshot(temporaryRoot.resolve("docs/components")))
    }

    @Test
    fun checkerAggregatesMissingUnexpectedDifferentAndReadmeFailures() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        ShowcaseStorage.writeStaging(output)
        ShowcaseSynchronizer.synchronize(launch, output)
        Files.delete(temporaryRoot.resolve("docs/components/row.md"))
        Files.write(temporaryRoot.resolve("docs/components/images/overview.png"), byteArrayOf(9))
        Files.writeString(temporaryRoot.resolve("docs/components/unexpected.md"), "unexpected")
        Files.writeString(temporaryRoot.resolve("README.md"), "changed")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseStorage.checkSource(temporaryRoot, output)
            }

        assertTrue(failure.message.orEmpty().contains("missing: row.md"))
        assertTrue(failure.message.orEmpty().contains("unexpected: unexpected.md"))
        assertTrue(failure.message.orEmpty().contains("different: images/overview.png"))
        assertTrue(failure.message.orEmpty().contains("README:"))
    }

    @Test
    fun invalidReadmePreflightLeavesStagingAndSourceUntouched() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme("<!-- strata-component-showcase:start -->\n<!-- strata-component-showcase:start -->\n<!-- strata-component-showcase:end -->\n")
        ShowcaseStorage.writeStaging(output)
        val beforeReadme = Files.readAllBytes(temporaryRoot.resolve("README.md"))
        val beforeStaging = snapshot(launch.stagingRoot)

        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseSynchronizer.synchronize(launch, output)
        }

        assertArrayEquals(beforeReadme, Files.readAllBytes(temporaryRoot.resolve("README.md")))
        assertEqualsSnapshot(beforeStaging, snapshot(launch.stagingRoot))
    }

    @Test
    fun malformedReadmeIsReportedAndLeavesSourceUntouched() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        Files.createDirectories(temporaryRoot.resolve("docs"))
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        Files.write(temporaryRoot.resolve("README.md"), malformed)
        ShowcaseStorage.writeStaging(output)
        val beforeStaging = snapshot(launch.stagingRoot)

        val checkFailure =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseStorage.checkSource(temporaryRoot, output)
            }
        assertTrue(checkFailure.message.orEmpty().contains("README must contain valid UTF-8"))
        val generationFailure =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseSynchronizer.synchronize(launch, output)
            }

        assertTrue(generationFailure.message.orEmpty().contains("README must contain valid UTF-8"))
        assertArrayEquals(malformed, Files.readAllBytes(temporaryRoot.resolve("README.md")))
        assertEqualsSnapshot(beforeStaging, snapshot(launch.stagingRoot))
        assertFalse(Files.exists(temporaryRoot.resolve("docs/components"), LinkOption.NOFOLLOW_LINKS))
        assertTransactionPathsAbsent(temporaryRoot)
    }

    @Test
    fun generationRemovesStaleFilesAndLeavesExactManifest() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        val target = temporaryRoot.resolve("docs/components")
        Files.createDirectories(target.resolve("images"))
        Files.writeString(target.resolve("stale.md"), "stale")
        Files.write(target.resolve("images/stale.png"), byteArrayOf(8))
        ShowcaseStorage.writeStaging(output)

        ShowcaseSynchronizer.synchronize(launch, output)

        assertEquals(
            setOf("README.md", "row.md", "images/overview.png", "images/row.png", "minecraft-26.2-parity.properties"),
            snapshot(target).keys,
        )
    }

    @Test
    fun secondGenerationIsIdempotentAndPreservesReadmeOutsideRegion() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        val prefix = "prefix-\u0000\n"
        val suffix = "\n\u0001-suffix"
        writeReadme("$prefix<!-- strata-component-showcase:start -->\nold\n<!-- strata-component-showcase:end -->$suffix")
        ShowcaseStorage.writeStaging(output)

        ShowcaseSynchronizer.synchronize(launch, output)
        val firstReadme = Files.readAllBytes(temporaryRoot.resolve("README.md"))
        val firstComponents = snapshot(temporaryRoot.resolve("docs/components"))
        ShowcaseSynchronizer.synchronize(launch, output)
        val secondReadme = Files.readAllBytes(temporaryRoot.resolve("README.md"))
        val secondComponents = snapshot(temporaryRoot.resolve("docs/components"))

        assertArrayEquals(firstReadme, secondReadme)
        assertEqualsSnapshot(firstComponents, secondComponents)
        assertArrayEquals(prefix.toByteArray(), secondReadme.copyOfRange(0, prefix.toByteArray().size))
        assertArrayEquals(suffix.toByteArray(), secondReadme.copyOfRange(secondReadme.size - suffix.toByteArray().size, secondReadme.size))
    }

    @Test
    fun eachReplacementMoveFailureRestoresSourceAndCleansTransactionPaths() {
        (1..4).forEach { failureIndex ->
            val caseRoot = temporaryRoot.resolve("failure-$failureIndex")
            val launch = launch(caseRoot)
            val output = output(launch.stagingRoot)
            writeReadme(root = caseRoot)
            val target = caseRoot.resolve("docs/components")
            Files.createDirectories(target)
            Files.writeString(target.resolve("old.md"), "old")
            ShowcaseStorage.writeStaging(output)
            val beforeReadme = Files.readAllBytes(caseRoot.resolve("README.md"))
            val beforeComponents = snapshot(target)
            val primary = IllegalStateException("replacement-$failureIndex")

            val thrown =
                assertThrows(Throwable::class.java) {
                    ShowcaseSynchronizer.synchronize(
                        launch,
                        output,
                        ScriptedFileSystem(FailurePoint(Operation.Move, failureIndex) to primary),
                    )
                }

            assertSame(primary, thrown)
            assertArrayEquals(beforeReadme, Files.readAllBytes(caseRoot.resolve("README.md")))
            assertEqualsSnapshot(beforeComponents, snapshot(target))
            assertTransactionPathsAbsent(caseRoot)
        }
    }

    @Test
    fun eachReplacementMoveAfterDelegateFailureRestoresSourceAndCleansTransactionPaths() {
        (1..4).forEach { failureIndex ->
            val caseRoot = temporaryRoot.resolve("after-failure-$failureIndex")
            val launch = launch(caseRoot)
            val output = output(launch.stagingRoot)
            writeReadme(root = caseRoot)
            val target = caseRoot.resolve("docs/components")
            Files.createDirectories(target)
            Files.writeString(target.resolve("old.md"), "old")
            ShowcaseStorage.writeStaging(output)
            val beforeReadme = Files.readAllBytes(caseRoot.resolve("README.md"))
            val beforeComponents = snapshot(target)
            val primary = IllegalStateException("replacement-after-$failureIndex")

            val thrown =
                assertThrows(Throwable::class.java) {
                    ShowcaseSynchronizer.synchronize(
                        launch,
                        output,
                        ScriptedFileSystem(
                            FailurePoint(Operation.Move, failureIndex, after = true) to primary,
                        ),
                    )
                }

            assertSame(primary, thrown)
            assertArrayEquals(beforeReadme, Files.readAllBytes(caseRoot.resolve("README.md")))
            assertEqualsSnapshot(beforeComponents, snapshot(target))
            assertTransactionPathsAbsent(caseRoot)
        }
    }

    @Test
    fun prepareCopyAndReadmeWriteFailuresCleanPartialPreparation() {
        val copyRoot = temporaryRoot.resolve("copy-failure")
        val copyLaunch = launch(copyRoot)
        val copyOutput = output(copyLaunch.stagingRoot)
        writeReadme(root = copyRoot)
        ShowcaseStorage.writeStaging(copyOutput)
        val copyPrimary = IllegalStateException("copy")
        val copyThrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    copyLaunch,
                    copyOutput,
                    ScriptedFileSystem(FailurePoint(Operation.Copy, 1, after = true) to copyPrimary),
                )
            }
        assertSame(copyPrimary, copyThrown)
        assertTransactionPathsAbsent(copyRoot)

        val writeRoot = temporaryRoot.resolve("write-failure")
        val writeLaunch = launch(writeRoot)
        val writeOutput = output(writeLaunch.stagingRoot)
        writeReadme(root = writeRoot)
        ShowcaseStorage.writeStaging(writeOutput)
        val writePrimary = IllegalStateException("readme-write")
        val writeThrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    writeLaunch,
                    writeOutput,
                    ScriptedFileSystem(FailurePoint(Operation.Write, 1, after = true) to writePrimary),
                )
            }
        assertSame(writePrimary, writeThrown)
        assertTransactionPathsAbsent(writeRoot)
    }

    @Test
    fun rollbackDeleteFailureRetainsBackupAndContinuesIndependentRecovery() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        val target = temporaryRoot.resolve("docs/components")
        Files.createDirectories(target)
        Files.writeString(target.resolve("old.md"), "old")
        ShowcaseStorage.writeStaging(output)
        val primary = IllegalStateException("replacement")
        val deleteFailure = IllegalArgumentException("target-delete")
        val restoreFailure = IllegalStateException("target-restore")

        val thrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    launch,
                    output,
                    ScriptedFileSystem(
                        FailurePoint(Operation.Move, 4) to primary,
                        FailurePoint(Operation.DeleteTree, 1) to deleteFailure,
                        FailurePoint(Operation.Move, 6) to restoreFailure,
                    ),
                )
            }

        assertSame(primary, thrown)
        assertEquals(listOf(deleteFailure, restoreFailure), thrown.suppressed.toList())
        assertTrue(Files.exists(temporaryRoot.resolve("docs/.strata-components-backup"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(temporaryRoot.resolve(".strata-readme-backup"), LinkOption.NOFOLLOW_LINKS).not())
    }

    @Test
    fun rollbackReadmeRestoreFailureRetainsBackupAndPreservesPrimary() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        val target = temporaryRoot.resolve("docs/components")
        Files.createDirectories(target)
        Files.writeString(target.resolve("old.md"), "old")
        ShowcaseStorage.writeStaging(output)
        val primary = IllegalStateException("replacement")
        val restoreFailure = IllegalArgumentException("readme-restore")

        val thrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    launch,
                    output,
                    ScriptedFileSystem(
                        FailurePoint(Operation.Move, 4) to primary,
                        FailurePoint(Operation.Move, 5) to restoreFailure,
                    ),
                )
            }

        assertSame(primary, thrown)
        assertEquals(listOf(restoreFailure), thrown.suppressed.toList())
        assertTrue(Files.exists(temporaryRoot.resolve(".strata-readme-backup"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(temporaryRoot.resolve("docs/.strata-components-backup"), LinkOption.NOFOLLOW_LINKS).not())
    }

    @Test
    fun rollbackReadmeDeleteFailurePreservesBackupAndContinuesRestore() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        val target = temporaryRoot.resolve("docs/components")
        Files.createDirectories(target)
        Files.writeString(target.resolve("old.md"), "old")
        ShowcaseStorage.writeStaging(output)
        val primary = IllegalStateException("replacement")
        val deleteFailure = IllegalArgumentException("readme-delete")

        val thrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    launch,
                    output,
                    ScriptedFileSystem(
                        FailurePoint(Operation.Move, 4, after = true) to primary,
                        FailurePoint(Operation.Delete, 1, after = true) to deleteFailure,
                    ),
                )
            }

        assertSame(primary, thrown)
        assertEquals(listOf(deleteFailure), thrown.suppressed.toList())
        assertArrayEquals(
            "before\n<!-- strata-component-showcase:start -->\n<!-- strata-component-showcase:end -->\nafter\n".toByteArray(),
            Files.readAllBytes(temporaryRoot.resolve("README.md")),
        )
        assertTrue(Files.exists(temporaryRoot.resolve(".strata-readme-backup"), LinkOption.NOFOLLOW_LINKS).not())
    }

    @Test
    fun temporaryCleanupFailuresRemainSuppressedAfterIndependentCleanup() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        val target = temporaryRoot.resolve("docs/components")
        Files.createDirectories(target)
        Files.writeString(target.resolve("old.md"), "old")
        ShowcaseStorage.writeStaging(output)
        val primary = IllegalStateException("replacement")
        val cleanupTreeFailure = IllegalArgumentException("next-tree-cleanup")
        val cleanupReadmeFailure = IllegalStateException("next-readme-cleanup")

        val thrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    launch,
                    output,
                    ScriptedFileSystem(
                        FailurePoint(Operation.Move, 2) to primary,
                        FailurePoint(Operation.DeleteTree, 1) to cleanupTreeFailure,
                        FailurePoint(Operation.Delete, 1) to cleanupReadmeFailure,
                    ),
                )
            }

        assertSame(primary, thrown)
        assertEquals(listOf(cleanupTreeFailure, cleanupReadmeFailure), thrown.suppressed.toList())
        assertTrue(Files.exists(temporaryRoot.resolve("docs/.strata-components-next"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(temporaryRoot.resolve(".strata-readme-next"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun successfulCommitCleanupFailuresUseFirstFailureAndKeepUpdatedSource() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        val target = temporaryRoot.resolve("docs/components")
        Files.createDirectories(target)
        Files.writeString(target.resolve("old.md"), "old")
        ShowcaseStorage.writeStaging(output)
        val cleanupTreeFailure = IllegalStateException("backup-tree-cleanup")
        val cleanupReadmeFailure = IllegalArgumentException("backup-readme-cleanup")

        val thrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    launch,
                    output,
                    ScriptedFileSystem(
                        FailurePoint(Operation.DeleteTree, 1) to cleanupTreeFailure,
                        FailurePoint(Operation.Delete, 1) to cleanupReadmeFailure,
                    ),
                )
            }

        assertSame(cleanupTreeFailure, thrown)
        assertEquals(listOf(cleanupReadmeFailure), thrown.suppressed.toList())
        assertTrue(Files.exists(target.resolve("row.md"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(temporaryRoot.resolve("docs/.strata-components-backup"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(temporaryRoot.resolve(".strata-readme-backup"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun duplicateAndCauseDirectionFailuresAreNotSuppressedTwice() {
        val sameFailure = IllegalStateException("same")
        val sameResult =
            synchronizeWithRollbackFailures(
                IllegalStateException("primary-same"),
                sameFailure,
                sameFailure,
            )
        assertEquals(1, sameResult.suppressed.size)

        val primaryCause = IllegalStateException("primary")
        val failureCause = IllegalArgumentException("failure")
        primaryCause.initCause(failureCause)
        val causeResult = synchronizeWithRollbackFailures(primaryCause, failureCause, IllegalStateException("independent"))
        assertEquals(1, causeResult.suppressed.size)
        assertEquals("independent", causeResult.suppressed.single().message)

        val reversePrimary = IllegalStateException("reverse-primary")
        val reverseFailure = IllegalArgumentException("reverse-failure")
        reverseFailure.initCause(reversePrimary)
        val reverseResult = synchronizeWithRollbackFailures(reversePrimary, reverseFailure, IllegalStateException("independent-reverse"))
        assertEquals(1, reverseResult.suppressed.size)
        assertEquals("independent-reverse", reverseResult.suppressed.single().message)

        val cyclePrimary = IllegalStateException("cycle-primary")
        val cycleFailure = IllegalArgumentException("cycle-failure")
        cyclePrimary.initCause(cycleFailure)
        cycleFailure.initCause(cyclePrimary)
        val cycleResult = synchronizeWithRollbackFailures(cyclePrimary, cycleFailure, IllegalStateException("cycle-independent"))
        assertEquals(1, cycleResult.suppressed.size)
        assertEquals("cycle-independent", cycleResult.suppressed.single().message)
    }

    private var suppressionCase: Int = 0

    private fun synchronizeWithRollbackFailures(
        primary: Throwable,
        firstRollbackFailure: Throwable,
        secondRollbackFailure: Throwable = firstRollbackFailure,
    ): Throwable {
        suppressionCase += 1
        val root = temporaryRoot.resolve("suppression-$suppressionCase")
        val launch = launch(root)
        val output = output(launch.stagingRoot)
        writeReadme(root = root)
        val target = root.resolve("docs/components")
        Files.createDirectories(target)
        Files.writeString(target.resolve("old.md"), "old")
        ShowcaseStorage.writeStaging(output)
        return assertThrows(Throwable::class.java) {
            ShowcaseSynchronizer.synchronize(
                launch,
                output,
                ScriptedFileSystem(
                    FailurePoint(Operation.Move, 4) to primary,
                    FailurePoint(Operation.Move, 5) to firstRollbackFailure,
                    FailurePoint(Operation.Move, 6) to secondRollbackFailure,
                ),
            )
        }
    }

    @Test
    fun rollbackFailurePreservesPrimaryAndSuppressesDistinctFailureOnce() {
        val launch = launch()
        val output = output(launch.stagingRoot)
        writeReadme()
        Files.createDirectories(temporaryRoot.resolve("docs/components"))
        ShowcaseStorage.writeStaging(output)
        val primary = IllegalStateException("replacement")
        val rollbackFailure = IllegalArgumentException("rollback")

        val thrown =
            assertThrows(Throwable::class.java) {
                ShowcaseSynchronizer.synchronize(
                    launch,
                    output,
                    ScriptedFileSystem(
                        FailurePoint(Operation.Move, 4) to primary,
                        FailurePoint(Operation.Move, 5) to rollbackFailure,
                    ),
                )
            }

        assertSame(primary, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(rollbackFailure, thrown.suppressed.single())
        assertTrue(thrown.suppressed.none { failure -> failure === thrown })
    }

    private fun launch(root: Path = temporaryRoot): ShowcaseLaunchArguments {
        val moduleBuild = root.resolve("integration/docs/build")
        val staging = moduleBuild.resolve("component-showcase/generate")
        val parity = root.resolve("integration/minecraft-fabric-26.2/build/minecraft-parity")
        val classes = root.resolve("api/classes")
        Files.createDirectories(staging)
        Files.createDirectories(parity)
        Files.createDirectories(classes)
        return ShowcaseLaunchArguments.parse(
            arrayOf(root.toString(), moduleBuild.toString(), staging.toString(), parity.toString(), classes.toString()),
            ShowcaseStagingKind.Generate,
        )
    }

    private fun output(staging: Path): ShowcaseOutput =
        ShowcaseOutput(
            ShowcaseOutput.Overview("overview\n", "`- Row\n", byteArrayOf(1, 2, 3)),
            listOf(ShowcaseOutput.Page(DocumentedComponent.Row, "# Row\n", byteArrayOf(4, 5, 6))),
            staging,
            "verified=true\n".toByteArray(),
        )

    private fun writeReadme(
        value: String = "before\n<!-- strata-component-showcase:start -->\n<!-- strata-component-showcase:end -->\nafter\n",
        root: Path = temporaryRoot,
    ) {
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("README.md"), value)
    }

    private fun snapshot(root: Path): Map<String, ByteArray> {
        if (Files.exists(root).not()) return emptyMap()
        Files.walk(root).use { stream ->
            return stream
                .filter { path -> Files.isRegularFile(path) }
                .toList()
                .associate { path -> root.relativize(path).toString().replace('\\', '/') to Files.readAllBytes(path) }
        }
    }

    private fun assertEqualsSnapshot(
        expected: Map<String, ByteArray>,
        actual: Map<String, ByteArray>,
    ) {
        assertTrue(expected.keys == actual.keys)
        expected.forEach { (path, bytes) -> assertArrayEquals(bytes, actual.getValue(path)) }
    }

    private fun assertTransactionPathsAbsent(root: Path) {
        listOf(
            root.resolve("docs/.strata-components-next"),
            root.resolve("docs/.strata-components-backup"),
            root.resolve(".strata-readme-next"),
            root.resolve(".strata-readme-backup"),
        ).forEach { path -> assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) }
    }

    private enum class Operation {
        Copy,
        Write,
        Move,
        Delete,
        DeleteTree,
    }

    private data class FailurePoint(
        val operation: Operation,
        val occurrence: Int,
        val after: Boolean = false,
    )

    private class ScriptedFileSystem(
        private val failures: Map<FailurePoint, Throwable>,
    ) : ShowcaseFileSystem {
        constructor(vararg failures: Pair<FailurePoint, Throwable>) : this(failures.toMap())

        private val counts = HashMap<Operation, Int>()

        override fun copy(
            source: Path,
            target: Path,
        ) {
            run(Operation.Copy) { NioShowcaseFileSystem.copy(source, target) }
        }

        override fun write(
            path: Path,
            bytes: ByteArray,
        ) {
            run(Operation.Write) { NioShowcaseFileSystem.write(path, bytes) }
        }

        override fun move(
            source: Path,
            target: Path,
        ) {
            run(Operation.Move) { Files.move(source, target) }
        }

        override fun delete(path: Path) {
            run(Operation.Delete) { NioShowcaseFileSystem.delete(path) }
        }

        override fun deleteTree(path: Path) {
            run(Operation.DeleteTree) { NioShowcaseFileSystem.deleteTree(path) }
        }

        private fun run(
            operation: Operation,
            delegate: () -> Unit,
        ) {
            val occurrence = (counts[operation] ?: 0) + 1
            counts[operation] = occurrence
            val before = failures[FailurePoint(operation, occurrence)]
            if (before != null) throw before
            delegate()
            val after = failures[FailurePoint(operation, occurrence, after = true)]
            if (after != null) throw after
        }
    }
}
