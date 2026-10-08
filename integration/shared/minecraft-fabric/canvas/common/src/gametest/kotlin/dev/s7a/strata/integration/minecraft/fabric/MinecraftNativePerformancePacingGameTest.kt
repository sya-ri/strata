package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletionException

/**
 * Exercises the compiled native pacing family and borrowed settings in ordinary loaded Canvas acceptance.
 * The runner owns failure assertions and files; actual option reads, setters, selectors and query owners run on the client.
 * These checks collect no performance samples and restore settings independently after every expected failure.
 */
internal object MinecraftNativePerformancePacingGameTest {
    /**
     * Verifies owner rejection, partial native application, readiness and restoration after actual loaded failures.
     * [nativeFailure] executes the existing real producer-failure acceptance inside a borrowed-option lifetime.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        nativeFailure: () -> Unit,
    ) {
        verifyOwner(context)
        verifyPartialApplication(context)
        var observation: MinecraftNativePerformancePacing? = null
        withBorrowedOptions(context) {
            observation = context.onClient { Minecraft.getInstance().nativePerformancePacing() }
        }
        verifyViewportFailure(context)
        val queryFailure = verifyQueryFailure(context)
        verifyPngFailure(context)
        withBorrowedOptions(context) { nativeFailure() }
        Files.writeString(
            context.outputDirectory.resolve("strata-native-pacing-restoration.txt"),
            "case=native-pacing-restoration\nowner=verified\ncapture=nonmutating\npartialNativeApplication=restored\n" +
                "viewportFailure=restored\nqueryFailure=$queryFailure\npngFailure=restored\nnativeProducerFailure=restored\n" +
                "pacing=${checkNotNull(observation)}\n",
        )
    }

    private fun verifyOwner(context: MinecraftCanvasTestContext) {
        val before = snapshot(context)
        check(runCatching { MinecraftNativePerformanceOptions() }.exceptionOrNull() is IllegalStateException)
        val options = context.onClient { MinecraftNativePerformanceOptions() }
        val outcome =
            runCatching {
                check(snapshot(context) == before) { "Capturing native options must not mutate them." }
                listOf<() -> Unit>(options::apply, options::close, { options.pacingReady() }).forEach { action ->
                    check(runCatching(action).exceptionOrNull() is IllegalStateException) { "A runner-thread operation reached borrowed native settings." }
                }
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { context.onClient { options.close() } }, { check(snapshot(context) == before) })
        outcome.getOrThrow()
    }

    private fun verifyPartialApplication(context: MinecraftCanvasTestContext) {
        val before = snapshot(context)
        context.onClient {
            val client = Minecraft.getInstance()
            val lease = MinecraftNativePerformanceOptionLease { check(client.isSameThread) }
            val vsync = client.options.enableVsync()
            val limit = client.options.framerateLimit()
            val marker = IOException("Expected failure after an actual native option setter")
            var fail = true
            lease.capture(vsync::get, { value ->
                vsync.set(value)
                if (fail) {
                    fail = false
                    throw marker
                }
            }, vsync.get().not())
            lease.capture(limit::get, limit::set, 120)
            client.captureNativeInactivity(lease, MinecraftNativePerformancePacing.Inactivity.MINIMIZED)
            val outcome = runCatching { lease.apply() }
            runCanvasTestCleanup(outcome.exceptionOrNull(), lease::close)
            check(outcome.exceptionOrNull() === marker && marker.suppressed.isEmpty()) { "Partial application replaced the exact native setter failure." }
        }
        check(snapshot(context) == before) { "Partial native application did not restore every captured setting." }
    }

    private fun verifyViewportFailure(context: MinecraftCanvasTestContext) {
        val failure =
            runCatching {
                withBorrowedOptions(context) { options ->
                    context.configureViewport(IntSize(640, 480), 1)
                    context.onClient { runCatching { options.validate(1) } }.getOrThrow()
                }
            }.exceptionOrNull()
        check(failure is IllegalStateException && failure.suppressed.isEmpty()) { "The actual incompatible viewport did not retain its validation failure: $failure" }
    }

    private fun verifyQueryFailure(context: MinecraftCanvasTestContext): String {
        val adapter =
            try {
                Class.forName("dev.s7a.strata.integration.minecraft.fabric.MinecraftGpuPerformanceProbe")
            } catch (_: ClassNotFoundException) {
                return "unavailable"
            }
        val failure =
            runCatching {
                withBorrowedOptions(context) {
                    val outcome =
                        context.onClient {
                            val probe = adapter.getConstructor(Int::class.javaPrimitiveType).newInstance(1) as MinecraftNativeGpuProbe
                            val result = runCatching { probe.append(JsonObject()) }
                            runCanvasTestCleanup(result.exceptionOrNull(), probe::close)
                            result
                        }
                    outcome.getOrThrow()
                }
            }.exceptionOrNull()
        check(failure is IllegalStateException && failure.suppressed.isEmpty()) { "An incomplete actual GPU query did not retain its failure: $failure" }
        return "restored"
    }

    private fun verifyPngFailure(context: MinecraftCanvasTestContext) {
        val blocker = Files.createTempFile(context.outputDirectory, "native-pacing-png-failure-", ".txt")
        val outcome =
            runCatching {
                withBorrowedOptions(context) {
                    context.onClient { captureMinecraftCanvasNativeFrame(blocker.resolve("frame.png")) }.join()
                }
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { Files.delete(blocker) })
        val failure = outcome.exceptionOrNull()
        check(failure != null && failure.suppressed.isEmpty() && (failure is IOException || (failure is CompletionException && failure.cause is IOException))) {
            "Writing an actual native PNG below a regular file did not retain its storage failure: $failure"
        }
    }

    private fun withBorrowedOptions(
        context: MinecraftCanvasTestContext,
        action: (MinecraftNativePerformanceOptions) -> Unit,
    ) {
        val before = snapshot(context)
        val options = context.onClient { MinecraftNativePerformanceOptions() }
        val outcome =
            runCatching {
                check(snapshot(context) == before) { "Native fixture capture mutated borrowed options." }
                context.onClient { options.apply() }
                context.waitFor { options.pacingReady() }
                action(options)
            }
        runCanvasTestCleanup(
            outcome.exceptionOrNull(),
            { context.onClient { options.close() } },
            { check(snapshot(context) == before) { "A loaded fixture lifetime did not restore its original native options." } },
        )
        outcome.getOrThrow()
    }

    private fun snapshot(context: MinecraftCanvasTestContext): Snapshot =
        context.onClient {
            val client = Minecraft.getInstance()
            Snapshot(client.options.enableVsync().get(), client.options.framerateLimit().get(), client.nativePerformancePacing().inactivity())
        }

    /**
     * Actual borrowed values; limiter observations may change naturally and are not substituted for option state.
     */
    private data class Snapshot(
        val vsync: Boolean,
        val limit: Int,
        val inactivity: MinecraftNativePerformancePacing.Inactivity,
    )
}
