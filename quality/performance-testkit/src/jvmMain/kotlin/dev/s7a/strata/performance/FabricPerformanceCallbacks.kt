package dev.s7a.strata.performance

import java.lang.reflect.ParameterizedType

/**
 * Fabric reflection boundary keeps the kit POM from replacing the measured Fabric/Strata version.
 */
internal object FabricPerformanceCallbacks {
    // External Fabric method names are the adapter contract.
    private const val CONTEXT_RUN_METHOD = "runOnClient"
    private const val EVENT_REGISTER_METHOD = "register"

    /**
     * Dispatches a kit operation through the actual loaded game-test context.
     */
    internal fun onClient(
        context: Any,
        operation: () -> Unit,
    ) {
        val method = context.javaClass.methods.single { it.name == CONTEXT_RUN_METHOD && it.parameterCount == 1 }
        val callback =
            HostReflection.callback(method.parameterTypes.single()) {
                operation()
                null
            }
        HostReflection.call(method, context, callback)
    }

    /**
     * Registers the extraction callback for the actual loaded screen-event family.
     */
    internal fun register(
        owner: Any,
        names: List<String>,
        operation: () -> Unit,
    ) {
        val events = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents", true, owner.javaClass.classLoader)
        val factory =
            names.firstNotNullOfOrNull { name -> events.methods.firstOrNull { it.name == name && it.parameterCount == 1 } }
                ?: error("Unsupported Fabric presentation callbacks: $names")
        val event = checkNotNull(HostReflection.call(factory, null, owner))
        val listener = (factory.genericReturnType as ParameterizedType).actualTypeArguments.single() as Class<*>
        val callback =
            HostReflection.callback(listener) {
                operation()
                null
            }
        val registration = event.javaClass.methods.single { it.name == EVENT_REGISTER_METHOD && it.parameterCount == 1 }
        HostReflection.call(registration, event, callback)
    }
}
