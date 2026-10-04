package dev.s7a.strata.performance

import java.lang.reflect.Method

/**
 * Loaded-host window validity checked before extraction timing, without linking a Minecraft version.
 * Modern hosts expose iconification directly; only legacy getWindow handles use GLFW.
 */
internal class NativeWindowGuard(
    loader: ClassLoader,
) {
    private val minimized: () -> Boolean

    init {
        val minecraft =
            try {
                Class.forName("net.minecraft.client.Minecraft", true, loader)
            } catch (_: ClassNotFoundException) {
                null
            }
        val (window, handle, legacy) =
            if (minecraft != null) {
                val client = checkNotNull(HostReflection.call(minecraft.getMethod("getInstance"), null))
                val actual = checkNotNull(HostReflection.invoke(client, "getWindow"))
                val handleMethod =
                    listOf("handle", "getWindow").firstNotNullOfOrNull { name ->
                        actual.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    } ?: error("Missing supported Minecraft Window native handle")
                Triple(actual, (HostReflection.call(handleMethod, actual) as Number).toLong(), handleMethod.name.contentEquals("getWindow"))
            } else {
                val mapped = NativeMappedWindowAccess.load(loader)
                Triple(mapped.first, mapped.second, true)
            }
        check(handle != 0L) { "Native benchmark has no live window" }
        val iconified =
            try {
                window.javaClass.getMethod("isIconified").also { check(it.returnType == Boolean::class.javaPrimitiveType) }
            } catch (_: NoSuchMethodException) {
                null
            }
        minimized =
            if (iconified != null) {
                { HostReflection.call(iconified, window) as Boolean }
            } else {
                check(legacy) { "The loaded Window has no supported iconification contract" }
                val glfw = Class.forName("org.lwjgl.glfw.GLFW", true, loader)
                val attribute: Method = glfw.getMethod("glfwGetWindowAttrib", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                val iconifiedAttribute = glfw.getField("GLFW_ICONIFIED").getInt(null)
                val query = { (HostReflection.call(attribute, null, handle, iconifiedAttribute) as Int) != 0 }
                query
            }
    }

    /**
     * Rejects minimized windows instead of recording throttled frames as normal evidence.
     */
    internal fun verify() {
        check(minimized().not()) { "Native benchmark window was minimized" }
    }
}
