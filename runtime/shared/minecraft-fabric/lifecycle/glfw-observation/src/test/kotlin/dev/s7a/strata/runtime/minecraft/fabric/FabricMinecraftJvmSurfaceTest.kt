@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.textures.GpuTexture
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.jvm.java

/**
 * Verifies intentional Java entrypoints, borrowed Canvas descriptors, and native construction restrictions.
 *
 * Tests inspect class metadata without constructing a client or native resource.
 * Compiler ABI validation owns the module-wide public contract; JVM flags on Kotlin internal implementation classes do not define consumer API.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftJvmSurfaceTest {
    @Test
    fun canvasFactoriesAndBorrowedContextExposeExactDescriptors() {
        val sources = Class.forName(canvasFacade)
        val textureFactory = sources.getDeclaredMethod("canvasSource", MinecraftCanvasTextureProvider::class.java)
        val rendererFactory =
            sources.getDeclaredMethod(
                "canvasSource",
                Boolean::class.javaPrimitiveType,
                Function0::class.java,
            )
        listOf(textureFactory, rendererFactory).forEach { method ->
            assertTrue(Modifier.isStatic(method.modifiers))
            assertEquals(CanvasSource::class.java, method.returnType)
        }
        assertEquals(
            MinecraftCanvasTextureLease::class.java,
            MinecraftCanvasTextureProvider::class.java.getDeclaredMethod("acquire").returnType,
        )
        assertEquals(GpuTexture::class.java, MinecraftCanvasTextureLease::class.java.getDeclaredMethod("getTexture").returnType)
        assertEquals(
            DrawImage::class.java,
            MinecraftCanvasRenderer::class.java.getDeclaredMethod("render", MinecraftCanvasContext::class.java).returnType,
        )

        val context = MinecraftCanvasContext::class.java
        mapOf(
            "getTarget" to RenderTarget::class.java,
            "getEncoder" to CommandEncoder::class.java,
            "getLogicalSize" to IntSize::class.java,
            "getPhysicalSize" to IntSize::class.java,
            "getFrameTime" to FrameTime::class.java,
        ).forEach { (method, returnType) ->
            assertEquals(returnType, context.getDeclaredMethod(method).returnType, method)
        }
        val visibleConstructors =
            context.declaredConstructors.filter { constructor ->
                Modifier.isPublic(constructor.modifiers) || Modifier.isProtected(constructor.modifiers)
            }
        assertTrue(visibleConstructors.all { constructor -> constructor.isSynthetic })
        assertEquals(
            List::class.java,
            FabricMinecraftScreen::class.java.getDeclaredMethod("captureCanvasFrame").returnType,
        )
    }

    @Test
    fun facadeDescriptorsAndScreenConstructionBoundaryAreExact() {
        val profileFactory = Class.forName(profileFacade).getDeclaredMethod("extractMinecraftUiProfile")
        assertTrue(Modifier.isStatic(profileFactory.modifiers))
        assertEquals(MinecraftUiProfile::class.java, profileFactory.returnType)

        val imageFactory = Class.forName(assetFacade).getDeclaredMethod("loadMinecraftUiImage", ResourceId::class.java)
        assertTrue(Modifier.isStatic(imageFactory.modifiers))
        assertEquals(DrawImage::class.java, imageFactory.returnType)
        val skinFactory = Class.forName(assetFacade).getDeclaredMethod("loadCurrentMinecraftPlayerSkin")
        assertTrue(Modifier.isStatic(skinFactory.modifiers))
        assertEquals(DrawImage::class.java, skinFactory.returnType)

        val screenFactory =
            Class.forName(screenFacade).getDeclaredMethod(
                "createMinecraftScreen",
                ScreenDefinition::class.java,
                MinecraftUiProfile::class.java,
                Screen::class.java,
            )
        assertTrue(Modifier.isStatic(screenFactory.modifiers))
        assertEquals(FabricMinecraftScreen::class.java, screenFactory.returnType)

        val background =
            FabricMinecraftScreen::class.java.getDeclaredMethod(
                "extractBackground",
                GuiGraphicsExtractor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
        assertTrue(Modifier.isPublic(background.modifiers))

        val screen = FabricMinecraftScreen::class.java
        assertFalse(screen.methods.any { it.name in setOf("startRenderMonitoring") })
        val externallyVisibleConstructors =
            screen.declaredConstructors.filter { constructor ->
                Modifier.isPublic(constructor.modifiers) || Modifier.isProtected(constructor.modifiers)
            }
        assertTrue(externallyVisibleConstructors.all { constructor -> constructor.isSynthetic })
        assertEquals(setOf("Companion"), screen.declaredFields.filter { field -> Modifier.isPublic(field.modifiers) }.mapTo(HashSet()) { field -> field.name })
    }

    private companion object {
        private val packageName = FabricMinecraftScreen::class.java.packageName
        private val assetFacade = "$packageName.FabricMinecraftAssets"
        private val profileFacade = "$packageName.FabricMinecraftProfiles"
        private val screenFacade = "$packageName.FabricMinecraftScreens"
        private val canvasFacade = "$packageName.MinecraftCanvasSources"
    }
}
