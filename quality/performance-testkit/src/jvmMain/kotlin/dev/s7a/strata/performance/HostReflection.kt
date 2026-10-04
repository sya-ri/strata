package dev.s7a.strata.performance

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Test-only host invocation without a transitive dependency that replaces the measured runtime.
 */
internal object HostReflection {
    /**
     * Unwraps application failures rather than reporting reflective envelopes.
     */
    internal fun call(
        method: Method,
        receiver: Any?,
        vararg arguments: Any?,
    ): Any? {
        check(method.trySetAccessible()) { "Inaccessible performance host method: $method" }
        return try {
            method.invoke(receiver, *arguments)
        } catch (failure: InvocationTargetException) {
            throw (failure.cause ?: failure)
        }
    }

    /**
     * Creates a native functional-interface callback while preserving Object method contracts.
     */
    internal fun callback(
        type: Class<*>,
        operation: (Array<out Any?>) -> Any?,
    ): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
            when (method) {
                Any::class.java.getMethod("equals", Any::class.java) -> proxy === arguments?.firstOrNull()
                Any::class.java.getMethod("hashCode") -> System.identityHashCode(proxy)
                Any::class.java.getMethod("toString") -> "Strata performance callback"
                else -> operation(arguments.orEmpty())
            }
        }

    /**
     * Calls a unique public host method with an exact argument count.
     */
    internal fun invoke(
        receiver: Any,
        name: String,
        vararg arguments: Any?,
    ): Any? {
        val method = receiver.javaClass.methods.single { it.name == name && it.parameterCount == arguments.size }
        return call(method, receiver, *arguments)
    }
}
