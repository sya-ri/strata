package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler

/**
 * Delivers physical coordinates through the unobfuscated native mouse callback on the client thread.
 */
internal fun moveStandaloneMinecraftPointer(
    minecraft: Minecraft,
    x: Double,
    y: Double,
) {
    val callback = MouseHandler::class.java.getDeclaredMethod("onMove", Long::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
    check(callback.trySetAccessible()) { "The native Minecraft pointer callback is inaccessible." }
    callback.invoke(minecraft.mouseHandler, minecraft.window.handle(), x, y)
}
