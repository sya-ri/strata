package dev.s7a.strata.quality.benchmark

/**
 * Exact currently loaded portable module boundaries admitted by executable JVM workload families.
 */
internal enum class RuntimeSurfaceFeature(
    val module: String,
    val representative: String,
    val sourceOwner: String,
) {
    Api("api", "dev.s7a.strata.component.UiScope", "api/src/"),
    Core("core", "dev.s7a.strata.runtime.spi.RuntimeUiSession", "runtime/core/src/"),
    Minecraft("minecraft", "dev.s7a.strata.runtime.minecraft.MinecraftUiHost", "runtime/minecraft/src/"),
    Fonts("fonts", "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory", "runtime/minecraft-fonts-lwjgl/src/"),
}
