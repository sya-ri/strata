package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.lwjgl.sdl.SDLError.SDL_GetError
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowMinimumSize
import org.lwjgl.sdl.SDLVideo.SDL_SetWindowMinimumSize
import org.lwjgl.system.MemoryStack

/**
 * Temporarily permits the existing small acceptance viewports on this invocation's native SDL window.
 * The action restores its starting viewport before returning; the original native minimum is restored on success or failure.
 * All native access remains on the client thread, and cleanup preserves the primary assertion failure.
 */
internal fun <T> withSmallMinecraftTestWindow(
    context: MinecraftLoadedTestContext,
    action: () -> T,
): T {
    val original =
        context.computeOnClient { minecraft ->
            val handle = minecraft.window.handle()
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                check(SDL_GetWindowMinimumSize(handle, width, height)) { "Cannot read the native window minimum: ${SDL_GetError()}" }
                check(SDL_SetWindowMinimumSize(handle, 1, 1)) { "Cannot configure the acceptance window minimum: ${SDL_GetError()}" }
                handle to IntSize(width[0], height[0])
            }
        }
    val result = runCatching(action)
    runCanvasTestCleanup(result.exceptionOrNull(), {
        context.runOnClient { minecraft ->
            check(minecraft.window.handle() == original.first) { "The acceptance window was replaced before minimum restoration." }
            check(SDL_SetWindowMinimumSize(original.first, original.second.width, original.second.height)) { "Cannot restore the native window minimum: ${SDL_GetError()}" }
        }
    })
    return result.getOrThrow()
}
