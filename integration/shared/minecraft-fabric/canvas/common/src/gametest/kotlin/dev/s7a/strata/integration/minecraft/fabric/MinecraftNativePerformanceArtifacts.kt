package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Captures the five shared runtime modules and native adapter through the actual production client loader.
 * Reads all class resources on the client owner and rejects unavailable archive or resource origins.
 * Development class directories cannot substitute for production archives.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun MinecraftCanvasTestContext.nativePerformanceRuntimeMetadata(): JsonObject =
    onClient {
        LoadedArtifactMetadata
            .capture(
                FabricMinecraftScreen::class.java.classLoader,
                mapOf(
                    "api" to "dev.s7a.strata.render.DrawImage",
                    "core" to "dev.s7a.strata.runtime.UiSession",
                    "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                    "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                    "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
                    "fabric" to FabricMinecraftScreen::class.java.name,
                ),
                setOf("api", "core", "headless", "minecraft", "fonts"),
            ).also(LoadedArtifactMetadata::verifyComplete)
    }
