package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.jvm.java

/**
 * Verifies that the Fabric adapter exposes only its intended entrypoint, asset, profile, screen factories and screen type.
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
        listOf(lifecycleClass, lifecycleActionClass, failuresClass, presentationTransactionClass).forEach { className ->
            val type = classes.single { candidate -> candidate.name == className }
            assertFalse(Modifier.isPublic(type.modifiers), className)
            assertFalse(Modifier.isProtected(type.modifiers), className)
        }
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

    private companion object {
        private val packageName = FabricMinecraftScreen::class.java.packageName
        private val assetFacade = "$packageName.FabricMinecraftAssets"
        private val profileFacade = "$packageName.FabricMinecraftProfiles"
        private val screenFacade = "$packageName.FabricMinecraftScreens"
        private val lifecycleClass = "$packageName.FabricScreenLifecycleTransaction"
        private val lifecycleActionClass = "$lifecycleClass\$Action"
        private val failuresClass = "$packageName.FabricMinecraftFailures"
        private val presentationTransactionClass = "$packageName.FabricScreenPresentationTransaction"
        private val expectedPublicMethods =
            mapOf(
                "$packageName.FabricMinecraftFontContractKt" to emptySet(),
                "$packageName.FabricMinecraftFocusedInputMappingKt" to emptySet(),
                "$packageName.FabricMinecraftGuiMetadataKt" to emptySet(),
                "$packageName.FabricMinecraftGuiScaling" to emptySet(),
                "$packageName.FabricMinecraftInputMappingKt" to emptySet(),
                "$packageName.FabricMinecraftNativeImageBridgeKt" to emptySet(),
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
                    ),
                "$packageName.FabricMinecraftScreen\$Companion" to emptySet(),
                screenFacade to setOf("createMinecraftScreen"),
                "$packageName.StrataFabricClient" to setOf("onInitializeClient"),
                "$packageName.FabricMinecraftTextMappingKt" to emptySet(),
            )
    }
}
