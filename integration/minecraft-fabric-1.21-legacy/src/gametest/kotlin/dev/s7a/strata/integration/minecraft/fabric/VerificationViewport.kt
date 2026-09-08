package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack

/**
 * Requests a physical viewport and waits for GLFW, Window, and the render target to agree across client ticks.
 *
 * Called from the test coordinator; all native reads and mutations run on the client thread.
 * A window resize request is asynchronous on Windows, so calling resizeDisplay immediately is not completion evidence.
 * The bounded wait preserves strict screenshot dimensions and reports the last native sizes on failure.
 */
internal fun MinecraftLoadedTestContext.configureVerificationViewport(
    size: IntSize,
    guiScale: Int,
) {
    computeOnClient { minecraft ->
        minecraft.window.setWindowed(size.width, size.height)
        minecraft.options.guiScale().set(guiScale)
        minecraft.resizeDisplay()
    }
    var consecutiveMatches = 0
    var mismatches = 0
    var lastDimensions = "not sampled"
    try {
        waitFor { minecraft ->
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                GLFW.glfwGetFramebufferSize(minecraftTestWindowHandle(), width, height)
                val window = minecraft.window
                val target = minecraft.mainRenderTarget
                lastDimensions = "GLFW=${width[0]}x${height[0]}, Window=${window.width}x${window.height}, target=${target.width}x${target.height}"
                val matches =
                    width[0] == size.width && height[0] == size.height &&
                        window.width == size.width && window.height == size.height &&
                        target.width == size.width && target.height == size.height
                if (matches) {
                    consecutiveMatches += 1
                } else {
                    consecutiveMatches = 0
                    mismatches += 1
                }
                2 <= consecutiveMatches
            }
        }
    } catch (failure: Throwable) {
        throw IllegalStateException("Viewport ${size.width}x${size.height} did not settle: $lastDimensions", failure)
    }
    if (0 < mismatches) {
        println("Verification viewport ${size.width}x${size.height} settled after $mismatches mismatched samples: $lastDimensions")
    }
}
