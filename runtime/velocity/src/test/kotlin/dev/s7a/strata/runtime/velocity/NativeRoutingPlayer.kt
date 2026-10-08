package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * Independently authenticated player/backend doubles for the real plugin callback.
 * Captures the exact arrays and callback ordering without copying; routing-lock tests serialize concurrent writes.
 * API method-name dispatch stays at this reflection boundary and follows the existing pinned Velocity test harness.
 */
@Suppress("StringLiteralComparison")
internal class NativeRoutingPlayer {
    val current = AtomicReference<ServerConnection?>()
    val clientWrites = mutableListOf<ByteArray>()
    val backendWrites = mutableListOf<ByteArray>()
    val backendDestinations = mutableListOf<ServerConnection>()
    var active = true
    var writeFailure: Throwable? = null
    val player: Player =
        proxy(Player::class.java) { _, name, arguments ->
            when (name) {
                "isActive" -> active
                "getCurrentServer" -> Optional.ofNullable(current.get())
                "sendPluginMessage" -> write(clientWrites, arguments)
                else -> error("Unexpected routing player API: $name")
            }
        }
    val backend = replacement()

    init {
        current.set(backend)
    }

    /**
     * A fresh source reference; optional equality deliberately cannot replace the handler's reference-identity gate.
     */
    fun replacement(equalAliases: Boolean = false): ServerConnection =
        proxy(ServerConnection::class.java, equalAliases) { instance, name, arguments ->
            when (name) {
                "getPlayer" -> player
                "sendPluginMessage" -> write(backendWrites, arguments).also { backendDestinations.add(instance as ServerConnection) }
                else -> error("Unexpected routing backend API: $name")
            }
        }

    /**
     * Drops only completed fixture output, never changing a runtime peer or its input snapshot.
     */
    fun clear() {
        clientWrites.clear()
        backendWrites.clear()
        backendDestinations.clear()
    }

    private fun write(
        target: MutableList<ByteArray>,
        arguments: Array<out Any?>,
    ): Boolean {
        writeFailure?.let { throw it }
        target.add(arguments[1] as ByteArray)
        return true
    }

    private fun <T> proxy(
        type: Class<T>,
        equalAliases: Boolean = false,
        invoke: (Any, String, Array<out Any?>) -> Any?,
    ): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, arguments ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> equalAliases || instance === arguments?.get(0)
                    "toString" -> type.simpleName
                    else -> invoke(instance, method.name, arguments ?: emptyArray())
                }
            },
        )
}
