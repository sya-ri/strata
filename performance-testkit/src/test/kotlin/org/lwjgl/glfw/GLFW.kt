package org.lwjgl.glfw

/**
 * Test-only GLFW boundary, proving minimized-window rejection rather than host compatibility.
 */
internal object GLFW {
    const val GLFW_ICONIFIED = 1
    var iconified = false

    /**
     * Returns the fixture state only for the registered fake window and attribute.
     */
    @JvmStatic
    fun glfwGetWindowAttrib(
        handle: Long,
        attribute: Int,
    ): Int {
        error("A modern Window handle must never be passed to GLFW: $handle/$attribute")
    }
}
