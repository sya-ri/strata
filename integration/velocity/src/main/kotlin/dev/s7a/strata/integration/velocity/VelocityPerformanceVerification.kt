package dev.s7a.strata.integration.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.integration.performance.ServerPerformanceInterval
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import dev.s7a.strata.velocity.VelocityUi
import dev.s7a.strata.velocity.event.StrataUiClosedEvent
import dev.s7a.strata.velocity.event.StrataUiOpenedEvent
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * Measures synchronous proxy-owner work after real backend replacement and acknowledged presentation acceptance.
 * Queue submission excludes execution; HUD close excludes the preceding open and subsequent client acknowledgement.
 * Readiness and asynchronous work must succeed before another kit-owned opportunity or evidence publication.
 */
@Suppress("TooManyFunctions") // One owner retains the interval, two live HUDs and their event/readiness lifetime.
internal class VelocityPerformanceVerification(
    private val plugin: VelocityAcceptancePlugin,
    private val proxy: ProxyServer,
    private val player: Player,
    directory: Path,
) : AutoCloseable {
    val directory: Path = directory.resolve("performance").resolve(UUID.randomUUID().toString())
    private val owner = Thread.currentThread()
    private val kit = Path.of(requireNotNull(System.getProperty("strata.velocity.performanceKit")))
    private val runId = requireNotNull(System.getProperty("strata.velocity.run"))
    private val workloads = VelocityPerformanceWorkload.entries.iterator()
    private var workload = workloads.next()
    private val sessions = mutableListOf<UiSession>()
    private val opened = mutableMapOf<UiSession, CompletableFuture<Unit>>()
    private val terminated = mutableMapOf<UiSession, CompletableFuture<Unit>>()
    private var pending: CompletableFuture<*>? = null
    private var capabilities: CompletableFuture<UiClientCapabilities?>? = null
    private var hudClosure: CompletableFuture<Unit>? = null
    private var interval: ServerPerformanceInterval? = null
    private var closed = false

    /**
     * Begins only with two negotiated HUD slots; every continuation touching state returns to the UI owner.
     */
    fun start(): CompletableFuture<Unit> {
        checkOwner()
        val readiness =
            runCatching {
                proxy.eventManager.register(plugin, this)
                VelocityUi.capabilities(player).orTimeout(30, TimeUnit.SECONDS)
            }.getOrElse { failure ->
                runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
                return CompletableFuture.failedFuture(failure)
            }
        return readiness
            .thenCompose { negotiated ->
                VelocityUi.execute(plugin) {
                    checkOwner()
                    check(2 <= checkNotNull(negotiated).hudLimit) { "The proxy fixture requires two negotiated HUD slots" }
                    interval = createInterval()
                }
            }.thenCompose { advance() }
            .handle { _, failure ->
                if (failure == null) {
                    CompletableFuture.completedFuture(Unit)
                } else {
                    VelocityUi.execute(plugin, ::close).handle<Unit> { _, cleanupFailure ->
                        if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        throw CompletionException(failure)
                    }
                }
            }.thenCompose { it }
    }

    private fun advance(): CompletableFuture<Unit> =
        prepare().thenCompose {
            VelocityUi
                .execute(plugin) {
                    checkOwner()
                    check(player.isActive) { "The measured player disconnected" }
                    checkNotNull(interval).advance()
                }.thenCompose { complete ->
                    checkNotNull(pending).orTimeout(30, TimeUnit.SECONDS).thenCompose {
                        VelocityUi.execute(plugin) {
                            checkOwner()
                            verify()
                            if (complete) {
                                checkNotNull(interval).write(directory.resolve("${workload.interval}.json"), runId, "velocity-ui-owner")
                                checkNotNull(interval).close()
                                interval = null
                                if (workloads.hasNext().not()) return@execute true
                                workload = workloads.next()
                                interval = createInterval()
                            }
                            false
                        }
                    }
                }.thenCompose { finished -> if (finished) CompletableFuture.completedFuture(Unit) else advance() }
        }

    private fun prepare(): CompletableFuture<Unit> {
        checkOwner()
        sessions.clear()
        pending = null
        capabilities = null
        hudClosure = null
        return (0 until workload.hudCount)
            .fold(CompletableFuture.completedFuture(Unit)) { prepared, _ ->
                prepared.thenCompose { openHud() }
            }.thenApply {
                checkOwner()
                hudClosure =
                    sessions.fold(CompletableFuture.completedFuture(Unit)) { previous, session ->
                        previous.thenCombine(checkNotNull(terminated[session])) { _, _ -> }
                    }
            }
    }

    private fun openHud(): CompletableFuture<Unit> =
        VelocityUi
            .open(plugin, player) {
                checkOwner()
                UiDefinition("Performance HUD", presentation = UiPresentation.Hud) {
                    Column { repeat(99) { Spacer() } }
                }
            }.thenCompose { session ->
                checkOwner()
                check(session.status !is UiSessionStatus.Closed) { "Proxy HUD admission failed: ${session.status}" }
                sessions += session
                terminated[session] = CompletableFuture()
                CompletableFuture<Unit>()
                    .also { readiness -> opened[session] = readiness }
                    .orTimeout(30, TimeUnit.SECONDS)
            }

    private fun createInterval(): ServerPerformanceInterval =
        ServerPerformanceInterval(
            kit,
            workload.interval,
            { operate() },
            { verify() },
            VelocityPerformanceWorkload.representatives,
            VelocityPerformanceWorkload.inputLabels,
        )

    private fun operate(): Int {
        pending =
            when (workload) {
                VelocityPerformanceWorkload.OwnerEntrySubmission -> {
                    VelocityUi.execute(plugin) { checkOwner() }
                }

                VelocityPerformanceWorkload.CapabilitiesSubmission -> {
                    VelocityUi.capabilities(player).also { submitted -> capabilities = submitted }
                }

                VelocityPerformanceWorkload.HudClose, VelocityPerformanceWorkload.HudPairClose -> {
                    sessions.forEach(UiSession::close)
                    checkNotNull(hudClosure)
                }
            }
        return 1
    }

    private fun verify() {
        checkOwner()
        check(checkNotNull(pending).isCompletedExceptionally.not())
        if (workload == VelocityPerformanceWorkload.CapabilitiesSubmission && checkNotNull(pending).isDone) {
            check(checkNotNull(checkNotNull(capabilities).getNow(null)).types.isNotEmpty())
        }
        sessions.forEach { session ->
            check(session.status == UiSessionStatus.Closed(UiCloseReason.Closed)) { "An acknowledged HUD did not close" }
        }
    }

    /**
     * Event threads only enqueue snapshots; matching and readiness state are confined to the UI owner.
     */
    @Subscribe
    @Suppress("unused") // Velocity invokes the registered listener through its event dispatcher.
    fun opened(event: StrataUiOpenedEvent) {
        if (event.player === player && event.ownerPlugin === plugin) {
            VelocityUi.execute(plugin) {
                if (closed) return@execute
                checkOwner()
                opened.remove(event.session)?.let { readiness ->
                    if (event.presentation == UiPresentation.Hud && event.session.presentation == UiPresentation.Hud) {
                        readiness.complete(Unit)
                    } else {
                        readiness.completeExceptionally(IllegalStateException("The opened performance session is not a HUD"))
                    }
                }
            }
        }
    }

    /**
     * Requires each actual server lifecycle notification before preparing another HUD group.
     */
    @Subscribe
    @Suppress("unused") // Velocity invokes the registered listener through its event dispatcher.
    fun terminated(event: StrataUiClosedEvent) {
        if (event.player === player && event.ownerPlugin === plugin) {
            VelocityUi.execute(plugin) {
                if (closed) return@execute
                checkOwner()
                terminated.remove(event.session)?.let { completion ->
                    if (event.reason == UiCloseReason.Closed) {
                        completion.complete(Unit)
                    } else {
                        completion.completeExceptionally(IllegalStateException("The performance HUD terminated unexpectedly: ${event.reason}"))
                    }
                }
            }
        }
    }

    override fun close() {
        check(Thread.currentThread() === owner)
        if (closed) return
        closed = true
        proxy.eventManager.unregisterListener(plugin, this)
        val failures = sessions.mapNotNull { session -> runCatching(session::close).exceptionOrNull() }.toMutableList()
        pending?.cancel(false)
        pending = null
        val stopped = IllegalStateException("Velocity performance fixture closed")
        opened.values.forEach { readiness -> readiness.completeExceptionally(stopped) }
        terminated.values.forEach { completion -> completion.completeExceptionally(stopped) }
        opened.clear()
        terminated.clear()
        sessions.clear()
        capabilities = null
        hudClosure = null
        val activeInterval = interval
        interval = null
        runCatching { activeInterval?.close() }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).filter { it !== failure }.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner && closed.not()) { "Proxy performance state escaped its UI owner" }
    }
}
