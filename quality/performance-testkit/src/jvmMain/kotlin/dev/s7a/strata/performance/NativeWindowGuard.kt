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
        val minecraft = Class.forName("net.minecraft.client.Minecraft", true, loader)
        val client = checkNotNull(HostReflection.call(minecraft.getMethod("getInstance"), null))
        val window = checkNotNull(HostReflection.invoke(client, "getWindow"))
        val handleMethod =
            listOf("handle", "getWindow").firstNotNullOfOrNull { name ->
                window.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            } ?: error("Missing supported Minecraft Window native handle")
        val handle = (HostReflection.call(handleMethod, window) as Number).toLong()
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
                check(handleMethod == window.javaClass.getMethod("getWindow")) { "The loaded Window has no supported iconification contract" }
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
