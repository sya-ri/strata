package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenRuntimeUnavailableException
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.tools.ToolProvider
import java.lang.reflect.Modifier as JvmModifier

/**
 * Compiles minimal Java call sites against published JVM contracts and checks their results in Kotlin.
 * Java syntax remains test input so Kotlin overload resolution and spread copies cannot hide interoperability regressions.
 */
internal class JavaConsumerApiTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun compiledJavaConsumersPreserveFacadeAndOwnershipContracts() {
        compileConsumer().use { loader ->
            val consumer = loader.loadClass("Consumer")
            verifyTextOwnership(consumer)
            verifyPublicValues(consumer)
            verifyHeadlessFacades(consumer)
        }
        verifyHeadlessSurface()
    }

    private fun verifyTextOwnership(consumer: Class<*>) {
        val first = UiText.Literal("first")
        val second = UiText.Literal("second")
        val source = arrayOf<UiText>(first, second, first)
        val constructed = consumer.call("construct", source) as UiText.Concatenated
        val combined = consumer.call("concat", source) as UiText.Concatenated
        val hash = constructed.hashCode()
        val text = constructed.toString()
        source[0] = second
        source[1] = first
        assertEquals(listOf(first, second, first), constructed.parts)
        assertEquals(constructed, combined)
        assertSame(first, constructed.parts[0])
        assertSame(first, combined.parts[2])
        assertEquals(hash, constructed.hashCode())
        assertEquals(text, constructed.toString())
        val singleton = arrayOf<UiText>(first)
        val singleConstructed = consumer.call("construct", singleton) as UiText.Concatenated
        val singleCombined = consumer.call("concat", singleton)
        singleton[0] = second
        assertSame(first, singleConstructed.parts[0])
        assertSame(first, singleCombined)
    }

    private fun verifyPublicValues(consumer: Class<*>) {
        val profile = UUID(Long.MIN_VALUE, -1L)
        assertSame(profile, (consumer.call("profile", profile) as PlayerSkinSource.Uuid).value)
        assertSame(SemanticsRole.TextArea, consumer.call("role"))
        assertNotEquals(SemanticsRole.TextField, consumer.call("role"))
        assertNotSame(Modifier, consumer.call("activate", true))
        assertSame(Modifier, consumer.call("activate", false))
        assertThrows<ScreenRuntimeUnavailableException> { consumer.call("open") }
        val image = consumer.call("image") as DrawImage
        assertEquals(IntSize(1, 1), image.size)
        assertEquals(0xFF123456.toInt(), image.argbAt(0, 0))
        assertArrayEquals(intArrayOf(0xFF123456.toInt()), image.copyArgb())
        val command = consumer.call("blit", image) as DrawCommand.BlitImage
        assertSame(image, command.image)
        assertEquals(IntRect(0, 0, 1, 1), command.source)
        assertEquals(IntRect(2, 3, 4, 5), command.destination)
    }

    private fun verifyHeadlessFacades(consumer: Class<*>) {
        assertEquals(IntSize(1, 1), (consumer.call("rasterize", false) as HeadlessImage).size)
        assertEquals(IntSize(2, 2), (consumer.call("rasterize", true) as HeadlessImage).size)
        assertThrows<NullPointerException> { consumer.call("render", false) }
        assertThrows<NullPointerException> { consumer.call("render", true) }
        assertThrows<IllegalArgumentException> { consumer.call("invalidCommands") }
    }

    private fun verifyHeadlessSurface() {
        for ((type, names) in listOf(HeadlessImage::class.java to setOf("getSize", "argbAt", "copyArgb", "encodePng"), HeadlessFrame::class.java to setOf("getViewport", "getPixelScale", "getImage", "getSemantics"))) {
            assertTrue(type.isSealed && type.isInterface)
            assertTrue(type.declaredConstructors.isEmpty() && type.declaredFields.isEmpty() && type.declaredClasses.isEmpty())
            assertTrue(type.permittedSubclasses.isNotEmpty())
            type.permittedSubclasses.forEach { assertTrue(JvmModifier.isPublic(it.modifiers).not() && JvmModifier.isProtected(it.modifiers).not()) }
            assertEquals(names, type.declaredMethods.map { it.name }.toSet())
            assertEquals(names.size, type.declaredMethods.size)
            type.declaredMethods.forEach { assertTrue(JvmModifier.isPublic(it.modifiers) && JvmModifier.isAbstract(it.modifiers) && it.isSynthetic.not()) }
        }
        val facade = Class.forName("dev.s7a.strata.runtime.headless.HeadlessRendering")
        assertTrue(facade.declaredMethods.none { it.name.startsWith("access$") })
        val methods = facade.declaredMethods.filter { JvmModifier.isPublic(it.modifiers) && it.isSynthetic.not() }
        assertEquals(4, methods.size)
        assertTrue(methods.all { JvmModifier.isStatic(it.modifiers) })
        for (scaled in listOf(false, true)) {
            val tail = if (scaled) arrayOf(IntSize::class.java, Int::class.javaPrimitiveType) else arrayOf(IntSize::class.java)
            assertEquals(HeadlessImage::class.java, facade.getDeclaredMethod("rasterizeHeadless", List::class.java, *tail).returnType)
            assertEquals(HeadlessFrame::class.java, facade.getDeclaredMethod("renderHeadless", Class.forName("dev.s7a.strata.element.Element"), *tail).returnType)
        }
        assertEquals(IntSize::class.java, HeadlessImage::class.java.getDeclaredMethod("getSize").returnType)
        assertEquals(Int::class.javaPrimitiveType, HeadlessImage::class.java.getDeclaredMethod("argbAt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).returnType)
        assertEquals(IntArray::class.java, HeadlessImage::class.java.getDeclaredMethod("copyArgb").returnType)
        assertEquals(ByteArray::class.java, HeadlessImage::class.java.getDeclaredMethod("encodePng").returnType)
        assertEquals(IntSize::class.java, HeadlessFrame::class.java.getDeclaredMethod("getViewport").returnType)
        assertEquals(Int::class.javaPrimitiveType, HeadlessFrame::class.java.getDeclaredMethod("getPixelScale").returnType)
        assertEquals(HeadlessImage::class.java, HeadlessFrame::class.java.getDeclaredMethod("getImage").returnType)
        assertEquals(List::class.java, HeadlessFrame::class.java.getDeclaredMethod("getSemantics").returnType)
    }

    private fun Class<*>.call(
        name: String,
        vararg arguments: Any,
    ): Any? =
        try {
            declaredMethods.single { it.name.contentEquals(name) }.invoke(null, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }

    private fun compileConsumer(): URLClassLoader {
        val source = directory.resolve("Consumer.java")
        Files.writeString(source, JAVA_CALLS)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Java consumer verification requires a full JDK." }
        val classpath =
            listOf(UiText::class.java, HeadlessImage::class.java, DrawCommand::class.java, Unit::class.java)
                .map {
                    Path
                        .of(
                            it.protectionDomain.codeSource.location
                                .toURI(),
                        ).toString()
                }.distinct()
                .joinToString(File.pathSeparator)
        compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { files ->
            assertTrue(compiler.getTask(null, files, null, listOf("-classpath", classpath, "-d", directory.toString()), null, files.getJavaFileObjects(source.toFile())).call())
        }
        return URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader)
    }

    private companion object {
        val JAVA_CALLS =
            """
            import dev.s7a.strata.component.PlayerSkinSource;
            import dev.s7a.strata.geometry.IntRect;
            import dev.s7a.strata.geometry.IntSize;
            import dev.s7a.strata.modifier.Modifier;
            import dev.s7a.strata.modifier.ActivationExtensionsKt;
            import dev.s7a.strata.render.DrawImage;
            import dev.s7a.strata.render.DrawImages;
            import dev.s7a.strata.runtime.headless.HeadlessFrame;
            import dev.s7a.strata.runtime.headless.HeadlessImage;
            import dev.s7a.strata.runtime.headless.HeadlessRendering;
            import dev.s7a.strata.runtime.render.DrawCommand;
            import dev.s7a.strata.screen.ScreenDefinition;
            import dev.s7a.strata.screen.Screens;
            import dev.s7a.strata.semantics.SemanticsRole;
            import dev.s7a.strata.text.UiText;
            import java.util.Collections;
            import java.util.List;
            import java.util.UUID;
            import kotlin.Unit;

            @SuppressWarnings("deprecation")
            public final class Consumer {
                public static UiText.Concatenated construct(UiText[] parts) { return new UiText.Concatenated(parts); }
                public static UiText concat(UiText[] parts) { return UiText.Companion.concat(parts); }
                public static PlayerSkinSource.Uuid profile(UUID id) { return new PlayerSkinSource.Uuid(id); }
                public static SemanticsRole role() { return SemanticsRole.TextArea.INSTANCE; }
                public static Modifier activate(boolean enabled) {
                    return enabled ? ActivationExtensionsKt.onActivate(Modifier.Companion, session -> Unit.INSTANCE)
                        : ActivationExtensionsKt.onActivate(Modifier.Companion, false, session -> Unit.INSTANCE);
                }
                public static void open() {
                    ScreenDefinition definition = new ScreenDefinition("java", false, scope -> null);
                    try { Screens.open(definition); } finally { definition.close(); }
                }
                public static DrawImage image() { return DrawImages.createDrawImage(new IntSize(1, 1), new int[] {0xFF123456}); }
                public static DrawCommand.BlitImage blit(DrawImage image) { return new DrawCommand.BlitImage(image, new IntRect(0, 0, 1, 1), new IntRect(2, 3, 4, 5)); }
                public static HeadlessImage rasterize(boolean scaled) { return scaled ? HeadlessRendering.rasterizeHeadless(List.of(), new IntSize(1, 1), 2) : HeadlessRendering.rasterizeHeadless(List.of(), new IntSize(1, 1)); }
                public static HeadlessFrame render(boolean scaled) { return scaled ? HeadlessRendering.renderHeadless(null, new IntSize(0, 1), 1) : HeadlessRendering.renderHeadless(null, new IntSize(0, 1)); }
                public static HeadlessImage invalidCommands() { return HeadlessRendering.rasterizeHeadless(Collections.singletonList(null), new IntSize(1, 1)); }
            }
            """.trimIndent()
    }
}
