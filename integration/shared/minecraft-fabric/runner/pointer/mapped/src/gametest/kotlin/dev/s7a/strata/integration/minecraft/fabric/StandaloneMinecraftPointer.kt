package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/**
 * Delivers physical coordinates through the mapped native mouse callback on the client thread.
 */
internal fun moveStandaloneMinecraftPointer(
    minecraft: Minecraft,
    x: Double,
    y: Double,
) {
    val name = FabricLoader.getInstance().mappingResolver.mapMethodName("intermediary", "net.minecraft.class_312", "method_1600", "(JDD)V")
    val handler = minecraft.mouseHandler
    val callback = handler.javaClass.getDeclaredMethod(name, Long::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
    check(callback.trySetAccessible()) { "The native Minecraft pointer callback is inaccessible." }
    callback.invoke(handler, minecraftTestWindowHandle(), x, y)
}
