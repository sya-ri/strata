package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.api.ClientModInitializer
import net.minecraft.CrashReport
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Starts Strata's loaded-client acceptance suite on Minecraft 1.20.1 before Fabric Client GameTest.
 *
 * The entrypoint owns one daemon test thread, stops Minecraft after success, and converts assertion or runner failures into a client crash so Gradle observes a failing process.
 */
public class StrataMinecraftLegacyClientTest : ClientModInitializer {
    /** Starts the standalone loaded-client test after Fabric initializes the client. */
    override fun onInitializeClient() {
        val context = StandaloneMinecraftLoadedTestContext()
        Thread(
            {
                runCatching {
                    context.waitFor(CLIENT_START_TIMEOUT_TICKS) { minecraft ->
                        minecraft.overlay == null && minecraft.screen != null
                    }
                    context.runLegacySuite()
                }.onSuccess {
                    logger.info("Strata loaded-client verification passed")
                    context.computeOnClient { minecraft -> minecraft.stop() }
                }.onFailure { failure ->
                    logger.error("Strata loaded-client verification failed", failure)
                    runCatching {
                        context.computeOnClient { minecraft ->
                            minecraft.delayCrash(CrashReport.forThrowable(failure, "Strata loaded-client verification"))
                        }
                    }.onFailure {
                        exitProcess(1)
                    }
                }
            },
            "Strata loaded-client test",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(StrataMinecraftLegacyClientTest::class.java)
        private const val CLIENT_START_TIMEOUT_TICKS = 1_200
    }
}
