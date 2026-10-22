@file:JvmName("GLFW")

package org.lwjgl.glfw

/**
 * Attribute field expected by the reflective GLFW fallback in the window guard.
 */
public const val GLFW_ICONIFIED: Int = 1

/**
 * Fails an accidental GLFW lookup; modern window fixtures must read iconification from their own owner.
 */
public fun glfwGetWindowAttrib(
    handle: Long,
    attribute: Int,
): Int = error("A modern Window handle must never be passed to GLFW: $handle/$attribute")
