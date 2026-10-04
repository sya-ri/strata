package dev.s7a.strata.integration.paper

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.integration.performance.ServerPerformanceInterval
import dev.s7a.strata.paper.PaperUi
import dev.s7a.strata.paper.open
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.UUID

/**
 * Actual server-owner operations after the independent acknowledged presentation acceptance scene.
 * This fixture supplies operations and checks; the shared kit owns warm-up, sample counts, deadlines and metrics.
 * One operation advances per primary-thread tick, without flooding one tick with repeated UI lifetimes.
 * Opening/closing measures real server projection and plugin-message submission, without timing native application.
 */
internal class PaperPerformanceVerification(
    private val plugin: Plugin,
    private val player: Player,
) : AutoCloseable {
    val directory: Path =
        plugin.dataFolder
            .toPath()
            .resolve("performance")
            .resolve(UUID.randomUUID().toString())
    private val kit = Path.of(requireNotNull(System.getProperty("strata.paper.performanceKit")))
    private val runId = requireNotNull(System.getProperty("strata.paper.run"))
    private val workloads = PaperPerformanceWorkload.entries.iterator()
    private var workload = workloads.next()
    private var latest: UiSession? = null
    private var capabilities = 0
    private var entered = false
    private var interval = createInterval()
    private var closed = false

    /**
     * Advances one scheduled opportunity and publishes only a fully collected workload.
     */
    fun tick(): Boolean {
        check(Bukkit.isPrimaryThread() && closed.not() && player.isOnline)
        if (interval.advance().not()) return false
        interval.write(directory.resolve("${workload.interval}.json"), runId, "paper-primary-owner")
        interval.close()
        if (workloads.hasNext().not()) return true
        workload = workloads.next()
        interval = createInterval()
        return false
    }

    private fun createInterval(): ServerPerformanceInterval =
        ServerPerformanceInterval(
            kit,
            workload.interval,
            {
                when (workload) {
                    PaperPerformanceWorkload.OwnerEntry -> {
                        entered = false
                        PaperUi.execute(player) { entered = true }
                    }

                    PaperPerformanceWorkload.Capabilities -> {
                        capabilities = checkNotNull(PaperUi.capabilities(player)).types.size
                    }

                    PaperPerformanceWorkload.HudLifetime -> {
                        latest = null
                        UiDefinition("Performance HUD", presentation = UiPresentation.Hud) {
                            Column { repeat(99) { Spacer() } }
                        }.open(plugin, player).use { session -> latest = session }
                    }
                }
                1
            },
            {
                when (workload) {
                    PaperPerformanceWorkload.OwnerEntry -> check(entered)
                    PaperPerformanceWorkload.Capabilities -> check(0 < capabilities)
                    PaperPerformanceWorkload.HudLifetime -> check(checkNotNull(latest).status == UiSessionStatus.Closed(UiCloseReason.Closed)) { "HUD lifetime was not admitted and closed by its owner" }
                }
            },
            PaperPerformanceWorkload.representatives,
            PaperPerformanceWorkload.inputLabels,
        )

    override fun close() {
        if (closed) return
        closed = true
        try {
            latest?.close()
        } finally {
            latest = null
            interval.close()
        }
    }
}
