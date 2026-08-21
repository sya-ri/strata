package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies the menu and printable-text elements exposed by the callback context.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftUiElementTest {
    @Test
    fun menuBackgroundUsesFullSourceAndRowMajorThirtyTwoPixelTiles() {
        val host = host { buildUi { MenuBackground() } }
        host.attach()

        val frame = host.frame(IntSize(64, 48))
        val commands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(4, commands.size)
        assertEquals(
            listOf(
                IntRect(0, 0, 32, 32),
                IntRect(32, 0, 64, 32),
                IntRect(0, 32, 32, 64),
                IntRect(32, 32, 64, 64),
            ),
            commands.map { command -> command.destination },
        )
        assertEquals(IntRect(0, 0, 16, 16), commands.first().source)
        host.close()
    }

    @Test
    fun menuBackgroundPreservesNearestMappingAcrossTileEdges() {
        val source =
            createDrawImage(
                IntSize(16, 16),
                IntArray(256) { index -> 0xFF000000.toInt() or index },
            )
        val host =
            createMinecraftUiHost(
                createMinecraftScreenDefinition(UiText.Literal("menu")) { buildUi { MenuBackground() } },
                MinecraftProfileFixture.create(source),
            )
        host.attach()
        val frame = host.frame(IntSize(40, 40))
        val commands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        commands.forEach { command -> assertSame(source, command.image) }
        val image = rasterizeHeadless(frame.drawCommands, IntSize(40, 40))
        assertEquals(source.argbAt(15, 15), image.argbAt(31, 31))
        assertEquals(source.argbAt(0, 0), image.argbAt(32, 0))
        assertEquals(source.argbAt(3, 3), image.argbAt(39, 39))

        val smallFrame = host.frame(IntSize(17, 15))
        val smallCommands = smallFrame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(1, smallCommands.size)
        assertEquals(smallFrame.drawCommands, smallCommands)
        assertTrue(smallFrame.semantics.isEmpty())
        val smallImage = rasterizeHeadless(smallFrame.drawCommands, IntSize(17, 15))
        assertEquals(source.argbAt(8, 7), smallImage.argbAt(16, 14))
        assertSame(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        host.close()
    }

    @Test
    fun zeroMenuAxisEmitsNoCommands() {
        val host = host { buildUi { MenuBackground() } }
        host.attach()
        assertEquals(emptyList<DrawCommand>(), host.frame(IntSize(0, 8)).drawCommands)
        assertEquals(emptyList<DrawCommand>(), host.frame(IntSize(8, 0)).drawCommands)
        host.close()
    }

    @Test
    fun menuPreflightsFinalTileOverflowBeforeIteration() {
        val host = host { buildUi { MenuBackground() } }
        host.attach()
        assertThrows(ArithmeticException::class.java) {
            host.frame(IntSize(Int.MAX_VALUE - 1, 1))
        }
        host.close()
    }

    @Test
    fun menuBackgroundRejectsAnUnboundedAxisInTheCoreTree() {
        var element: Element? = null
        val host = host { buildUi { MenuBackground() }.also { element = it } }
        host.attach()
        try {
            listOf(
                Constraints(maxWidth = Int.MAX_VALUE, maxHeight = 8),
                Constraints(maxWidth = 8, maxHeight = Int.MAX_VALUE),
            ).forEach { constraints ->
                val tree = UiTree()
                try {
                    tree.update(checkNotNull(element))
                    assertThrows(IllegalArgumentException::class.java) {
                        tree.measure(constraints)
                    }
                } finally {
                    tree.close()
                }
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun menuValidationAndImageUpdatesUseTheStableRetainedType() {
        val first = createDrawImage(IntSize(16, 16), IntArray(256) { 0xFF101010.toInt() })
        val second = createDrawImage(IntSize(16, 16), IntArray(256) { 0xFF202020.toInt() })
        val tree = UiTree()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                tree.update(createMinecraftMenuBackgroundElement(image(0), Modifier.Empty, null))
            }
            tree.update(createMinecraftMenuBackgroundElement(first, Modifier.Empty, null))
            tree.measure(Constraints.fixed(32, 32))
            tree.layout()
            assertSame(
                first,
                tree
                    .paint()
                    .filterIsInstance<DrawCommand.BlitImage>()
                    .single()
                    .image,
            )
            tree.update(createMinecraftMenuBackgroundElement(first, Modifier.Empty, null))
            assertSame(
                first,
                tree
                    .paint()
                    .filterIsInstance<DrawCommand.BlitImage>()
                    .single()
                    .image,
            )
            tree.update(createMinecraftMenuBackgroundElement(second, Modifier.Empty, null))
            assertSame(
                second,
                tree
                    .paint()
                    .filterIsInstance<DrawCommand.BlitImage>()
                    .single()
                    .image,
            )
        } finally {
            tree.close()
        }
    }

    @Test
    fun textPaintsShadowBeforeForegroundAndEmitsExactSemantics() {
        val text = UiText.Literal("A B")
        val host = host { buildUi { Text(text) } }
        host.attach()

        val frame = host.frame(IntSize(9, 9))
        val commands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(4, commands.size)
        assertEquals(0xFF3F3F3F.toInt(), commands[0].image.argbAt(0, 0))
        assertEquals(0xFFFFFFFF.toInt(), commands[1].image.argbAt(0, 0))
        assertEquals(IntRect(1, 1, 9, 9), commands[0].destination)
        assertEquals(IntRect(0, 0, 8, 8), commands[1].destination)
        assertEquals(IntRect(7, 1, 15, 9), commands[2].destination)
        assertEquals(IntRect(6, 0, 14, 8), commands[3].destination)
        assertEquals(1, frame.semantics.size)
        assertSame(
            text,
            frame.semantics
                .single()
                .semantics.label,
        )
        assertEquals(
            SemanticsRole.Text,
            frame.semantics
                .single()
                .semantics.role,
        )
        val rendered = rasterizeHeadless(frame.drawCommands, IntSize(9, 9))
        assertEquals(0xFFFFFFFF.toInt(), rendered.argbAt(0, 0))
        assertEquals(0xFF3F3F3F.toInt(), rendered.argbAt(1, 1))
        assertEquals(0xFFFFFFFF.toInt(), rendered.argbAt(7, 0))
        host.close()
    }

    @Test
    fun textUsesRightmostAdvanceAndImplicitSpaceWidth() {
        val emptyHost = host { buildUi { Text("") } }
        emptyHost.attach()
        assertEquals(IntSize(0, 9), emptyHost.frame(IntSize(0, 9)).size)
        emptyHost.close()

        val spaceHost = host { buildUi { Text(" ") } }
        spaceHost.attach()
        assertEquals(IntSize(4, 9), spaceHost.frame(IntSize(4, 9)).size)
        spaceHost.close()

        val leftmostHost = host { buildUi { Text("A") } }
        leftmostHost.attach()
        assertEquals(IntSize(2, 9), leftmostHost.frame(IntSize(2, 9)).size)
        leftmostHost.close()

        val rightmostHost = host { buildUi { Text("H") } }
        rightmostHost.attach()
        assertEquals(IntSize(9, 9), rightmostHost.frame(IntSize(9, 9)).size)
        rightmostHost.close()

        val allPrintable = (0x21..0x7E).map(Int::toChar).joinToString(separator = "")
        val allWidth = (0x21..0x7E).sumOf { codePoint -> (codePoint - 0x21) % 8 + 2 }
        val allHost = host { buildUi { Text(allPrintable) } }
        allHost.attach()
        val allFrame = allHost.frame(IntSize(allWidth, 9))
        assertEquals(IntSize(allWidth, 9), allFrame.size)
        assertEquals(188, allFrame.drawCommands.size)
        allHost.close()
    }

    @Test
    fun textRetainsSelectedImageIdentityInCharacterOrder() {
        val normalShadowA = image(0xFF101010.toInt())
        val normalForegroundA = image(0xFF202020.toInt())
        val normalShadowB = image(0xFF303030.toInt())
        val normalForegroundB = image(0xFF404040.toInt())
        val glyphA = glyph(2, normalShadowA, normalForegroundA)
        val glyphB = glyph(3, normalShadowB, normalForegroundB)
        val run =
            MinecraftTextRun.createNormal(UiText.Literal("AB")) { codePoint ->
                if (codePoint == 'A'.code) glyphA else glyphB
            }
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(run, Modifier.Empty, null))
            tree.measure(Constraints.fixed(5, 9))
            tree.layout()
            val commands = tree.paint().filterIsInstance<DrawCommand.BlitImage>()
            val expected = listOf(normalShadowA, normalForegroundA, normalShadowB, normalForegroundB)
            assertEquals(expected, commands.map { command -> command.image })
            commands.forEachIndexed { index, command -> assertSame(expected[index], command.image) }
            val rendered = rasterizeHeadless(commands, IntSize(11, 9))
            assertEquals(0xFF202020.toInt(), rendered.argbAt(1, 1))
            assertEquals(0xFF404040.toInt(), rendered.argbAt(3, 1))
            assertEquals(0xFF303030.toInt(), rendered.argbAt(10, 1))
        } finally {
            tree.close()
        }
    }

    @Test
    fun inactiveTextRunSelectsInactiveLayers() {
        val shadow = image(0xFF282828.toInt())
        val foreground = image(0xFFA0A0A0.toInt())
        val glyph = glyph(2, image(0xFF3F3F3F.toInt()), image(-1), shadow, foreground)
        val run = MinecraftTextRun.createInactive(UiText.Literal("A")) { glyph }
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(run, Modifier.Empty, null))
            tree.measure(Constraints.fixed(2, 9))
            tree.layout()
            val commands = tree.paint().filterIsInstance<DrawCommand.BlitImage>()
            assertSame(shadow, commands[0].image)
            assertSame(foreground, commands[1].image)
            val rendered = rasterizeHeadless(commands, IntSize(9, 9))
            assertEquals(0xFFA0A0A0.toInt(), rendered.argbAt(1, 1))
            assertEquals(0xFF282828.toInt(), rendered.argbAt(8, 1))
        } finally {
            tree.close()
        }
    }

    @Test
    fun equalAndChangedTextRunsReuseThenUpdateTheRetainedNode() {
        val firstShadow = image(0xFF101010.toInt())
        val firstForeground = image(0xFF202020.toInt())
        val secondShadow = image(0xFF303030.toInt())
        val secondForeground = image(0xFF404040.toInt())
        val firstGlyph = glyph(2, firstShadow, firstForeground)
        val secondGlyph = glyph(3, secondShadow, secondForeground)
        val firstRun = MinecraftTextRun.createNormal(UiText.Literal("A")) { firstGlyph }
        val equalRun = MinecraftTextRun.createNormal(UiText.Literal("A")) { firstGlyph }
        val secondRun = MinecraftTextRun.createNormal(UiText.Literal("B")) { secondGlyph }
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(firstRun, Modifier.Empty, null))
            tree.measure(Constraints.fixed(2, 9))
            tree.layout()
            assertSame(firstForeground, tree.paint().filterIsInstance<DrawCommand.BlitImage>()[1].image)
            tree.update(createMinecraftTextElement(equalRun, Modifier.Empty, null))
            assertSame(firstForeground, tree.paint().filterIsInstance<DrawCommand.BlitImage>()[1].image)
            tree.update(createMinecraftTextElement(secondRun, Modifier.Empty, null))
            tree.measure(Constraints.fixed(3, 9))
            tree.layout()
            assertSame(secondForeground, tree.paint().filterIsInstance<DrawCommand.BlitImage>()[1].image)
        } finally {
            tree.close()
        }
    }

    @Test
    fun textAdvanceChangesRemeasureWhenLiteralAndImagesAreEqual() {
        val shadow = image(0xFF101010.toInt())
        val foreground = image(0xFF202020.toInt())
        val narrowRun = MinecraftTextRun.createNormal(UiText.Literal("A")) { glyph(2, shadow, foreground) }
        val wideRun = MinecraftTextRun.createNormal(UiText.Literal("A")) { glyph(3, shadow, foreground) }
        val constraints = Constraints(maxWidth = 10, maxHeight = 10)
        val tree = UiTree()
        try {
            tree.update(createMinecraftTextElement(narrowRun, Modifier.Empty, null))
            assertEquals(IntSize(2, 9), tree.measure(constraints))
            tree.update(createMinecraftTextElement(wideRun, Modifier.Empty, null))
            assertEquals(IntSize(3, 9), tree.measure(constraints))
        } finally {
            tree.close()
        }
    }

    @Test
    fun unsupportedTextFailsDuringFirstAttach() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                val host = host { buildUi { Text(UiText.Translated("menu.title")) } }
                host.attach()
            }
        assertEquals("Common Minecraft text currently requires UiText.Literal.", failure.message)
    }

    @Test
    fun everyNonLiteralAndUnsupportedLiteralFormFailsWithoutFallback() {
        val unsupported =
            listOf<UiText>(
                UiText.Translated("menu.title"),
                UiText.Concatenated(UiText.Literal("A")),
                UiText.Platform(object : PlatformText {}),
                UiText.Literal("\u0000"),
                UiText.Literal("\u0009"),
                UiText.Literal("\u007F"),
                UiText.Literal("\u0080"),
                UiText.Literal("\uD83D\uDE00"),
            )
        unsupported.forEach { text ->
            val unsupportedHost = host { buildUi { Text(text) } }
            assertThrows(IllegalArgumentException::class.java) { unsupportedHost.attach() }
            unsupportedHost.close()
        }
    }

    @Test
    fun surrogateAndInsufficientConstraintsFailWithoutFallback() {
        val surrogateHost = host { buildUi { Text("\uD83D\uDE00") } }
        assertThrows(IllegalArgumentException::class.java) { surrogateHost.attach() }
        surrogateHost.close()

        val constrainedHost = host { buildUi { Text("A") } }
        constrainedHost.attach()
        assertThrows(IllegalArgumentException::class.java) {
            constrainedHost.frame(IntSize(1, 1))
        }
        constrainedHost.close()
    }

    @Test
    fun emptyAndSpaceTextUseNaturalSizeWithoutGlyphCommands() {
        val emptyHost = host { buildUi { Text("") } }
        emptyHost.attach()
        assertEquals(IntSize(0, 9), emptyHost.frame(IntSize(0, 9)).size)
        assertEquals(emptyList<DrawCommand>(), emptyHost.frame(IntSize(0, 9)).drawCommands)
        emptyHost.close()

        val spaceHost = host { buildUi { Text(" ") } }
        spaceHost.attach()
        val frame = spaceHost.frame(IntSize(4, 9))
        assertEquals(IntSize(4, 9), frame.size)
        assertEquals(emptyList<DrawCommand>(), frame.drawCommands)
        spaceHost.close()
    }

    @Test
    fun escapedContextIsClosedAfterContentCallback() {
        var escaped: MinecraftUiContext? = null
        val host =
            host {
                escaped = this
                buildUi { MenuBackground() }
            }
        host.attach()
        assertThrows(IllegalStateException::class.java) {
            with(checkNotNull(escaped)) { buildUi { MenuBackground() } }
        }
        val profileField = checkNotNull(escaped).javaClass.getDeclaredField("profile")
        profileField.isAccessible = true
        assertNull(profileField.get(escaped))
        host.close()
    }

    @Test
    fun contextRejectsWrongThreadWhileCallbackIsActive() {
        var wrongThreadFailure: Throwable? = null
        var wrongThreadRunner: Thread? = null
        val host =
            host {
                val context = this
                val task =
                    FutureTask<Throwable?> {
                        runCatching { with(context) { buildUi { MenuBackground() } } }.exceptionOrNull()
                    }
                val runner = Thread(task)
                wrongThreadRunner = runner
                runner.start()
                wrongThreadFailure = task.get(5, TimeUnit.SECONDS)
                buildUi { MenuBackground() }
            }
        try {
            host.attach()
            assertTrue(wrongThreadFailure is IllegalStateException)
        } finally {
            wrongThreadRunner?.join(5_000)
            host.close()
        }
    }

    private fun host(content: MinecraftUiContext.() -> Element): MinecraftUiHost =
        createMinecraftUiHost(
            createMinecraftScreenDefinition(UiText.Literal("test"), content = content),
            MinecraftProfileFixture.create(),
        )

    private fun glyph(
        advance: Int,
        shadow: DrawImage,
        foreground: DrawImage,
        inactiveShadow: DrawImage = shadow,
        inactiveForeground: DrawImage = foreground,
    ): MinecraftGlyphSnapshot =
        MinecraftGlyphSnapshot.create(
            advance,
            shadow,
            foreground,
            inactiveShadow,
            inactiveForeground,
            shadow,
            foreground,
            inactiveShadow,
            inactiveForeground,
            foreground,
        )

    private fun image(color: Int) = createDrawImage(IntSize(8, 8), IntArray(64) { color })
}
