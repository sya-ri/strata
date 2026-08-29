package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.buffers.GpuBufferSlice
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
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.gui.screens.Screen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.jvm.java

/**
 * Verifies the intended Java entrypoints, Canvas contracts, and narrowly required opt-in native bridges.
 *
 * Tests inspect class metadata without constructing a client or native resource.
 * Kotlin-only implementation methods remain synthetic, and generated accessors are forbidden even on hidden classes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftJvmSurfaceTest {
    @Test
    fun moduleClassesExposeOnlyTheIntendedNonSyntheticJavaSurface() {
        val classes = loadModuleClasses()
        val publicClasses =
            classes
                .filter { type -> Modifier.isPublic(type.modifiers) && type.isSynthetic.not() }
                .associateBy(Class<*>::getName)
        assertEquals(expectedPublicMethods.keys, publicClasses.keys)
        expectedPublicMethods.forEach { (className, methodNames) ->
            val actual =
                publicClasses
                    .getValue(className)
                    .declaredMethods
                    .filter { method -> Modifier.isPublic(method.modifiers) && method.isSynthetic.not() }
                    .mapTo(sortedSetOf()) { method -> method.name }
            assertEquals(methodNames, actual, className)
        }
        classes.forEach { type ->
            type.declaredMethods.forEach { method -> assertFalse(method.name.startsWith("access$"), "${type.name}#${method.name}") }
        }
        listOf(
            lifecycleClass,
            lifecycleActionClass,
            failuresClass,
            presentationTransactionClass,
            textInputFocusClass,
            inputResetClass,
            frameMixinClass,
        ).forEach { className ->
            val type = classes.single { candidate -> candidate.name == className }
            assertFalse(Modifier.isPublic(type.modifiers), className)
            assertFalse(Modifier.isProtected(type.modifiers), className)
        }
    }

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
        val externallyVisibleConstructors =
            screen.declaredConstructors.filter { constructor ->
                Modifier.isPublic(constructor.modifiers) || Modifier.isProtected(constructor.modifiers)
            }
        assertTrue(externallyVisibleConstructors.all { constructor -> constructor.isSynthetic })
        assertEquals(setOf("Companion"), screen.declaredFields.filter { field -> Modifier.isPublic(field.modifiers) }.mapTo(HashSet()) { field -> field.name })
    }

    private fun loadModuleClasses(): List<Class<*>> {
        val outputRoots =
            listOf(screenFacade, lifecycleClass)
                .map { className ->
                    val location =
                        Class
                            .forName(className)
                            .protectionDomain
                            .codeSource
                            .location
                            .toURI()
                    Path.of(location)
                }.distinct()
        return outputRoots
            .flatMap(::loadPackageClasses)
            .distinctBy(Class<*>::getName)
    }

    private fun loadPackageClasses(outputRoot: Path): List<Class<*>> {
        val packageRoot = outputRoot.resolve(packageName.replace('.', '/'))
        return Files.walk(packageRoot).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { path -> path.fileName.toString().endsWith(".class") }
                .map { path ->
                    val binaryName =
                        outputRoot
                            .relativize(path)
                            .toString()
                            .replace('\\', '.')
                            .replace('/', '.')
                            .removeSuffix(".class")
                    Class.forName(binaryName, false, FabricMinecraftScreen::class.java.classLoader)
                }.toList()
        }
    }

    private enum class GuiConsumerFamily(
        val parameterTypes: List<Class<*>>,
    ) {
        /** The GUI consumer borrows an explicit projection buffer during submission. */
        ProjectedBuffer(listOf(GpuBufferSlice::class.java)),

        /** The GUI consumer submits its already prepared native state without parameters. */
        DirectSubmission(emptyList()),
    }

    private companion object {
        private val packageName = FabricMinecraftScreen::class.java.packageName
        private val assetFacade = "$packageName.FabricMinecraftAssets"
        private val profileFacade = "$packageName.FabricMinecraftProfiles"
        private val screenFacade = "$packageName.FabricMinecraftScreens"
        private val canvasFacade = "$packageName.MinecraftCanvasSources"
        private val lifecycleClass = "$packageName.FabricScreenLifecycleTransaction"
        private val lifecycleActionClass = "$lifecycleClass\$Action"
        private val failuresClass = "$packageName.FabricMinecraftFailures"
        private val presentationTransactionClass = "$packageName.FabricScreenPresentationTransaction"
        private val textInputFocusClass = "$packageName.FabricMinecraftTextInputFocus"
        private val inputResetClass = "$packageName.FabricMinecraftInputReset"
        private val frameMixinClass = "$packageName.mixin.frame.FabricMinecraftCanvasRenderFrameMixin"
        private val canvasPublicMethods =
            mapOf(
                canvasFacade to setOf("canvasSource"),
                "$packageName.MinecraftCanvasTextureProvider" to setOf("acquire"),
                "$packageName.MinecraftCanvasTextureLease" to setOf("getTexture", "getSize", "getOrigin", "getSnapshot", "close"),
                "$packageName.MinecraftCanvasTextureOrigin" to setOf("values", "valueOf", "getEntries"),
                "$packageName.MinecraftCanvasRenderer" to setOf("render", "close"),
                "$packageName.MinecraftCanvasContext" to setOf("getTarget", "getEncoder", "getLogicalSize", "getPhysicalSize", "getFrameTime"),
                "$packageName.MinecraftCanvasContext\$Companion" to emptySet(),
            )
        private val canvasImplementationMethods =
            listOf(
                "$packageName.FabricMinecraftCanvasPresentation",
                "$packageName.FabricMinecraftPortableFrames",
                "$packageName.FabricMinecraftPortableImage",
                "$packageName.FabricMinecraftPortableTexture",
                "$packageName.FabricMinecraftPortableTexture\$Companion",
                "$packageName.FabricMinecraftPortableTextureFactoryKt",
                "$packageName.FabricNativeCanvasTarget",
                "$packageName.FabricNativeCanvasTarget\$Companion",
                "$packageName.FabricNativeCanvasPartialTarget",
                "$packageName.FabricNativeCanvasPartialTarget\$Companion",
                "$packageName.FabricNativeCanvasGpuFence",
                "$packageName.FabricNativeCanvasDriver",
                "$packageName.FabricNativeCanvasTextureProducer",
                "$packageName.FabricNativeCanvasRendererProducer",
                "$packageName.FabricNativeCanvasDestruction",
                "$packageName.FabricNativeCanvasShaders",
                "$packageName.FabricNativeCanvasSnapshotsKt",
                "$packageName.FabricNativeCanvasDrawingKt",
                "$packageName.FabricNativeCanvasTextureFactoryKt",
                "$packageName.FabricNativeCanvasDestructionFactoryKt",
                "$packageName.FabricNativeCanvasTargetDestructionKt",
            ).associateWith { emptySet<String>() }

        // Mixin code executes inside Minecraft packages, so these opt-in bridges must remain Java-accessible.
        private val canvasBridgeMethods =
            mapOf(
                "$packageName.FabricCanvasShutdownTransaction" to setOf("run"),
                "$packageName.FabricCanvasGuiCleanup" to setOf("run", "closeMeshes"),
                "$packageName.FabricCanvasGuiCleanup\$Cleanup" to setOf("run"),
                "$packageName.FabricMinecraftCanvasGuiDiscard" to setOf("strataDiscardCanvasGui"),
                "$packageName.FabricMinecraftCanvasGuiConsumption" to setOf("discardCanvasGui"),
                "$packageName.FabricMinecraftCanvasHooks" to
                    setOf("beginShutdown", "requireRunning", "afterGui", "afterFrame", "closeActiveScreen", "resetActiveInput"),
                "$packageName.mixin.canvas.FabricMinecraftCanvasGameRendererAccess" to setOf("strataCanvasGuiRenderer"),
                "$packageName.mixin.canvas.FabricMinecraftCanvasRenderStateAccess" to setOf("strataCanvasRenderState"),
            )

        // Select the native family from its upstream descriptor, never from Strata's observed public output or a version string.
        private fun canvasFamilyMethods(): Map<String, Set<String>> {
            val renderDescriptors =
                GuiRenderer::class.java.declaredMethods
                    .filter { method -> method.name == "render" }
                    .map { method -> method.parameterTypes.toList() }
            val family = GuiConsumerFamily.entries.single { candidate -> candidate.parameterTypes in renderDescriptors }
            return when (family) {
                GuiConsumerFamily.ProjectedBuffer -> {
                    mapOf("$packageName.FabricNativeCanvasPipelineKt" to emptySet())
                }

                GuiConsumerFamily.DirectSubmission -> {
                    mapOf(
                        "$packageName.FabricVulkanDestroyedResource" to setOf("strataCanvasResourceDestroyed"),
                        "$packageName.mixin.vulkan.FabricVulkanCanvasDeviceAccessor" to setOf("strataCanvasBackend"),
                        "$packageName.mixin.vulkan.FabricVulkanCanvasEncoderAccessor" to setOf("strataCanvasDestructionQueue"),
                    )
                }
            }
        }

        private val expectedPublicMethods =
            mapOf(
                "$packageName.FabricMinecraftFontCapabilitiesKt" to emptySet(),
                "$packageName.FabricMinecraftFontContractKt" to emptySet(),
                "$packageName.FabricMinecraftFontMappingKt" to emptySet(),
                "$packageName.FabricMinecraftFontResourcesKt" to emptySet(),
                "$packageName.FabricMinecraftFocusedInputMappingKt" to emptySet(),
                "$packageName.FabricMinecraftGuiMetadataKt" to emptySet(),
                "$packageName.FabricMinecraftGuiScaling" to emptySet(),
                "$packageName.FabricMinecraftInputMappingKt" to emptySet(),
                "$packageName.FabricMinecraftNativeImageBridgeKt" to emptySet(),
                "$packageName.FabricMinecraftProfileWidgetsKt" to emptySet(),
                "$packageName.FabricMinecraftProfileDecorationsKt" to emptySet(),
                "$packageName.FabricMinecraftProfileLifecycleKt" to emptySet(),
                "$packageName.FabricMinecraftSampledBoundsKt" to emptySet(),
                "$packageName.mixin.FabricMinecraftResourceReloadMixin" to emptySet(),
                "$packageName.FabricMinecraftWidgetImages" to emptySet(),
                assetFacade to setOf("loadMinecraftUiImage", "loadCurrentMinecraftPlayerSkin"),
                profileFacade to setOf("extractMinecraftUiProfile"),
                "$packageName.FabricMinecraftScreen" to
                    setOf(
                        "added",
                        "removed",
                        "extractBackground",
                        "extractRenderState",
                        "isPauseScreen",
                        "mouseMoved",
                        "mouseClicked",
                        "mouseReleased",
                        "mouseDragged",
                        "mouseScrolled",
                        "keyPressed",
                        "keyReleased",
                        "charTyped",
                        "preeditUpdated",
                        "onClose",
                        "close",
                        "captureCanvasFrame",
                    ),
                "$packageName.FabricMinecraftScreen\$Companion" to emptySet(),
                screenFacade to setOf("createMinecraftScreen"),
                "$packageName.StrataFabricClient" to setOf("onInitializeClient"),
                "$packageName.FabricMinecraftTextMappingKt" to emptySet(),
            ) + canvasPublicMethods + canvasImplementationMethods + canvasBridgeMethods + canvasFamilyMethods()
    }
}
