package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.api.ClientModInitializer
import net.minecraft.CrashReport
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Representative-only legacy entrypoint that runs native font parity before the existing acceptance suite.
 * It owns one daemon test thread, stops the game after success, and turns any failure into a failing client process.
 */
public class StrataMinecraftFontClientTest : ClientModInitializer {
    override fun onInitializeClient() {
        val context = StandaloneMinecraftLoadedTestContext()
        Thread(
            {
                runCatching {
                    context.waitFor(1_200) { minecraft -> minecraft.overlay == null && minecraft.screen != null }
                    MinecraftFontParitySuite.run(context)
                    context.runLegacySuite()
                }.onSuccess {
                    logger.info("Strata native font and loaded-client verification passed")
                    context.computeOnClient { minecraft -> minecraft.stop() }
                }.onFailure { failure ->
                    logger.error("Strata native font or loaded-client verification failed", failure)
                    runCatching {
                        context.computeOnClient { minecraft ->
                            minecraft.delayCrash(CrashReport.forThrowable(failure, "Strata native font verification"))
                        }
                    }.onFailure { exitProcess(1) }
                }
            },
            "Strata native font test",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(StrataMinecraftFontClientTest::class.java)
    }
}
