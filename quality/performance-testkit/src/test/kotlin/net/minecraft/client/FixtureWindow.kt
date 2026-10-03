package net.minecraft.client

/**
 * Test-only native handle owner; no operating-system window is created.
 */
internal class FixtureWindow {
    var iconified = false

    /**
     * Returns the fake modern owner handle, which must never be passed to GLFW.
     */
    @Suppress("FunctionOnlyReturningConstant") // Reflection requires the real Window method shape, rather than a fixture property.
    fun handle(): Long = 1L

    /**
     * Mirrors the modern SDL Window query without loading a native library.
     */
    fun isIconified(): Boolean = iconified
}
