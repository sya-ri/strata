package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.Window
import dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess
import net.minecraft.client.Minecraft

/**
 * Delivers native focus changes only for the active loaded client's window on its owner thread.
 * Callers restore the previous focus after verifying the production input-reset callback.
 */
internal object MinecraftCanvasWindowTestScope {
    /**
     * Invokes the real native focus callback without changing operating-system focus.
     */
    fun invoke(
        window: Window,
        handle: Long,
        focused: Boolean,
    ) {
        val client = Minecraft.getInstance()
        check(client.isSameThread && client.window === window) { "Native focus acceptance requires the active window on its client thread." }
        val access = MinecraftCanvasWindowTestAccess::class.java.cast(window)
        access.strataCanvasFocus(handle, focused)
    }
}
