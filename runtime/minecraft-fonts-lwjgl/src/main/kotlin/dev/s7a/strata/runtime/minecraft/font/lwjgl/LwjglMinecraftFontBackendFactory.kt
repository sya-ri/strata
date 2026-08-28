package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility

/**
 * Opens independent CPU font backends without Minecraft, a window, or a graphics context.
 * The application supplies the target release's LWJGL bindings, native classifiers, and ICU version on its runtime classpath.
 * This immutable factory owns no native state and is safe to share; every opened backend is confined to its opening thread.
 * Different LWJGL native generations must run in separate processes.
 * Opening or using a backend fails when required bindings or native libraries are absent; no operating-system font fallback is used.
 */
public object LwjglMinecraftFontBackendFactory : MinecraftFontBackendFactory {
    override fun open(compatibility: MinecraftFontCompatibility): MinecraftFontBackend = LwjglMinecraftFontBackend(compatibility.rasterizer)
}
