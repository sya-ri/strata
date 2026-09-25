package dev.s7a.strata.integration.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import dev.s7a.strata.velocity.VelocityUi
import net.kyori.adventure.text.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Owns one proxy screen transaction on the factory's thread, including a real native backend connection request.
 * A fresh receipt is written only after confirmed input and activation succeed on both backend generations.
 */
internal class VelocityAcceptanceSession(
    private val plugin: VelocityAcceptancePlugin,
    private val proxy: ProxyServer,
    private val player: Player,
    private val directory: Path,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val firstBackend = player.currentServer.orElseThrow()
    private val field = TextFieldState(maxLength = 64)
    private var handle: UiSession? = null
    private var switched: CompletableFuture<Boolean>? = null
    private var activations = 0
    private var presentations: VelocityUiPresentationVerification? = null

    /**
     * Retains the detached handle after its owner-thread open request completes.
     */
    fun bind(session: UiSession) {
        checkOwner()
        check(session.status !is UiSessionStatus.Closed) { "Proxy screen admission failed: ${session.status}" }
        handle = session
    }

    /**
     * Builds the first editable screen; activation validates the exact value before switching backend servers.
     */
    fun controls(): UiDefinition =
        editor("Strata proxy controls") {
            check(activations++ == 0)
            check(field.value.contentEquals("proxy-日本語")) { "Proxy did not receive confirmed text." }
            val next = proxy.allServers.single { it !== firstBackend.server }
            switched =
                player.createConnectionRequest(next).connect().thenApply { result ->
                    check(result.isSuccessful) { "Backend switch failed: ${result.status}" }
                    true
                }
        }

    /**
     * Requires terminal cleanup of the previous screen and renewed capabilities after backend replacement.
     */
    fun resume(): UiDefinition {
        checkOwner()
        check(checkNotNull(switched).getNow(false)) { "Backend switch has not completed." }
        check(player.currentServer.orElseThrow() !== firstBackend)
        check(handle?.status is UiSessionStatus.Closed) { "The old native-container generation is still active." }
        field.value = ""
        return editor("Strata proxy resumed") {
            check(activations++ == 1)
            check(field.value.contentEquals("resumed-日本語")) { "Post-switch text was not delivered." }
            handle?.close()
            val verification = VelocityUiPresentationVerification(plugin, proxy, player)
            presentations = verification
            verification
                .start()
                .thenCompose {
                    VelocityUi.execute(plugin) {
                        checkOwner()
                        Files.createDirectories(directory)
                        val run = requireNotNull(System.getProperty("strata.velocity.run"))
                        Files.writeString(directory.resolve("server.properties"), "runId=$run\nplayer=${player.uniqueId}\ntext=confirmed\nactivations=$activations\nbackendSwitch=confirmed\nownerThread=confirmed\nuiPresentations=confirmed\nuiEvents=confirmed\n")
                    }
                }.thenCompose {
                    VelocityUi.open(plugin, player) { UiDefinition("Strata proxy complete") { Column { Text("Proxy acceptance passed.") } } }
                }.whenComplete { _, failure ->
                    if (failure != null) proxy.consoleCommandSource.sendMessage(Component.text("Strata proxy HUD verification failed: $failure"))
                }
        }
    }

    private fun editor(
        title: String,
        action: () -> Unit,
    ): UiDefinition {
        checkOwner()
        return UiDefinition(title) {
            Column(Modifier.Empty.padding(8), spacing = 4) {
                TextField(field, IntSize(160, 20))
                Button(
                    "Apply",
                    160,
                    modifier =
                        Modifier.Empty.onActivate {
                            checkOwner()
                            action()
                        },
                )
            }
        }
    }

    override fun close() {
        checkOwner()
        handle?.close()
        handle = null
        switched = null
        presentations?.close()
        presentations = null
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Proxy state escaped its UI owner thread." }
    }
}
