@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies complete-profile validation and callback-lifetime ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftUiProfileTest {
    @Test
    fun missingRequiredSlotsAreRejectedIndependently() {
        completeProfile()
        assertMissing("Menu background must be declared.") {
            completeContainerAssets()
            completeScrollAssets()
            completeTextFieldAssets()
            completeGlyphs()
            completeButtons()
        }
        assertMissing("Every U+0021 through U+007E glyph must be declared.") {
            menuBackground(image(IntSize(16, 16)))
            completeContainerAssets()
            completeScrollAssets()
            completeTextFieldAssets()
            for (codePoint in 0x21 until 0x7E) printableAsciiGlyph(codePoint, image(IntSize(8, 8)))
            completeButtons()
        }
        assertMissing("Disabled Button sprite must be declared.") {
            completeMenuAndGlyphs()
            buttonNormal(buttonImage(), 1, NineSliceCenterMode.Tiled)
            buttonHighlighted(buttonImage(), 1, NineSliceCenterMode.Tiled)
        }
        assertMissing("Highlighted Button sprite must be declared.") {
            completeMenuAndGlyphs()
            buttonNormal(buttonImage(), 1, NineSliceCenterMode.Tiled)
            buttonDisabled(buttonImage(), 1, NineSliceCenterMode.Tiled)
        }
        assertMissing("Normal Button sprite must be declared.") {
            completeMenuAndGlyphs()
            buttonHighlighted(buttonImage(), 1, NineSliceCenterMode.Tiled)
            buttonDisabled(buttonImage(), 1, NineSliceCenterMode.Tiled)
        }
        TextFieldAsset.entries.forEach { omitted ->
            assertMissing("${omitted.label} must be declared.") {
                menuBackground(image(IntSize(16, 16)))
                completeContainerAssets()
                completeScrollAssets()
                completeTextFieldAssets(omitted)
                completeGlyphs()
                completeButtons()
            }
        }
    }

    @Test
    fun menuAndEachRequiredSlotRejectDuplicates() {
        assertDeclarationRejected("Menu background was already declared.", afterComplete = true) { menuBackground(image(IntSize(16, 16))) }
        assertDeclarationRejected("Glyph code point was already declared.", afterComplete = true) { printableAsciiGlyph(0x21, image(IntSize(8, 8))) }
        assertDeclarationRejected("Normal Button sprite was already declared.", afterComplete = true) { buttonNormal(buttonImage(), 1, NineSliceCenterMode.Tiled) }
        assertDeclarationRejected("Highlighted Button sprite was already declared.", afterComplete = true) { buttonHighlighted(buttonImage(), 1, NineSliceCenterMode.Tiled) }
        assertDeclarationRejected("Disabled Button sprite was already declared.", afterComplete = true) { buttonDisabled(buttonImage(), 1, NineSliceCenterMode.Tiled) }
        ScrollAsset.entries.forEach { duplicate ->
            assertDeclarationRejected("${duplicate.label} was already declared.", afterComplete = true) { declareScrollAsset(duplicate) }
        }
        TextFieldAsset.entries.forEach { duplicate ->
            assertDeclarationRejected("${duplicate.label} was already declared.", afterComplete = true) { declareTextFieldAsset(duplicate) }
        }
    }

    @Test
    fun eachRequiredScrollAssetIsRequiredAndHasAnExactSize() {
        completeProfile()
        ScrollAsset.entries.forEach { omitted ->
            assertMissing("${omitted.label} must be declared.") {
                menuBackground(image(IntSize(16, 16)))
                completeContainerAssets()
                completeScrollAssets(omitted)
                completeTextFieldAssets()
                completeGlyphs()
                completeButtons()
            }
        }
        ScrollAsset.entries.forEach { slot ->
            listOf(IntSize(slot.size.width - 1, slot.size.height), IntSize(slot.size.width, slot.size.height - 1)).forEach { size ->
                assertDeclarationRejected("${slot.label} must be ${slot.size.width} by ${slot.size.height} pixels.") {
                    declareScrollAsset(slot, size)
                }
            }
        }
    }

    @Test
    fun eachTextFieldSpriteRequiresTheExactSize() {
        TextFieldAsset.entries.forEach { slot ->
            listOf(IntSize(199, 20), IntSize(200, 19)).forEach { size ->
                assertDeclarationRejected("TextField sprites must be 200 by 20 pixels.") { declareTextFieldAsset(slot, size) }
            }
        }
    }

    @Test
    fun malformedMasksAndGlyphBoundsAreRejected() {
        assertDeclarationRejected("Glyph mask must be 8 by 8 pixels.") {
            printableAsciiGlyph(0x21, createDrawImage(IntSize(7, 8), IntArray(56)))
        }
        listOf(0x20, 0x7F).forEach { codePoint ->
            assertDeclarationRejected("Glyph code point must be U+0021 through U+007E.") { printableAsciiGlyph(codePoint, image(IntSize(8, 8))) }
        }
        assertDeclarationRejected("Glyph masks must contain only transparent white or opaque white pixels.") {
            printableAsciiGlyph(0x21, createDrawImage(IntSize(8, 8), IntArray(64) { 1 }))
        }
        assertDeclarationRejected("Glyph masks must contain only transparent white or opaque white pixels.") {
            printableAsciiGlyph(0x21, createDrawImage(IntSize(8, 8), IntArray(64) { index -> if (index == 8) 0 else 0x00FFFFFF }))
        }
    }

    @Test
    fun validTransparentAndOpaqueMaskPixelsAreAccepted() {
        val profile =
            completeProfile { codePoint ->
                when (codePoint) {
                    0x21 -> image(IntSize(8, 8))
                    0x22 -> maskAt(0)
                    0x23 -> maskAt(7)
                    else -> image(IntSize(8, 8))
                }
            }
        val empty = glyph(profile, 0x21)
        val left = glyph(profile, 0x22)
        val right = glyph(profile, 0x23)
        assertEquals(1, empty.advance)
        assertEquals(2, left.advance)
        assertEquals(9, right.advance)
        assertEquals(0xFF3F3F3F.toInt(), left.normalShadow.argbAt(0, 0))
        assertEquals(0xFFFFFFFF.toInt(), left.normalForeground.argbAt(0, 0))
        assertEquals(0xFF282828.toInt(), left.inactiveShadow.argbAt(0, 0))
        assertEquals(0xFFA0A0A0.toInt(), left.inactiveForeground.argbAt(0, 0))
        assertEquals(0xFF404040.toInt(), left.containerForeground.argbAt(0, 0))
        assertEquals(0x00FFFFFF, left.normalForeground.argbAt(1, 0))
        assertEquals(0xFFFFFFFF.toInt(), right.normalForeground.argbAt(7, 0))
    }

    @Test
    fun wrongMenuGlyphAndButtonSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile(menu = image(IntSize(15, 16)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile { codePoint ->
                if (codePoint == 0x21) image(IntSize(7, 8)) else image(IntSize(8, 8))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile(button = image(IntSize(199, 20)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile(button = image(IntSize(200, 19)))
        }
    }

    @Test
    fun containerAssetSlotsRejectMissingDuplicateAndWrongSizes() {
        completeProfile()
        ContainerAsset.entries.forEach { omitted ->
            assertMissing("${omitted.label} must be declared.") {
                menuBackground(image(IntSize(16, 16)))
                completeContainerAssets(omitted)
                completeScrollAssets()
                completeTextFieldAssets()
                completeGlyphs()
                completeButtons()
            }
        }
        ContainerAsset.entries.forEach { slot ->
            assertDeclarationRejected("${slot.label} was already declared.", afterComplete = true) { declareContainerAsset(slot) }
            val label = if (slot == ContainerAsset.Background) "Container background" else "Slot highlights"
            assertDeclarationRejected("$label must be ${slot.size.width} by ${slot.size.height} pixels.") {
                declareContainerAsset(slot, IntSize(slot.size.width - 1, slot.size.height))
            }
        }
    }

    @Test
    fun zeroNegativeAndSourceCenterBordersAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = 0) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = -1) }
        completeProfile(border = 99)
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = 100) }

        assertThrows(IllegalArgumentException::class.java) { completeProfile(normalBorder = 100) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(highlightedBorder = 100) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(disabledBorder = 100) }
    }

    @Test
    fun defaultFixtureUsesTheLockedMinecraftButtonPolicies() {
        val profile = MinecraftProfileFixture.create()
        val normal = button(profile, "normalButton")
        val highlighted = button(profile, "highlightedButton")
        val disabled = button(profile, "disabledButton")
        assertEquals(3, normal.border)
        assertEquals(3, highlighted.border)
        assertEquals(1, disabled.border)
        assertSame(NineSliceCenterMode.Tiled, normal.centerMode)
        assertSame(NineSliceCenterMode.Tiled, highlighted.centerMode)
        assertSame(NineSliceCenterMode.Tiled, disabled.centerMode)
    }

    @Test
    fun builderIsClosedAfterSuccessAndExactFailure() {
        var successful: MinecraftUiProfileBuilder? = null
        createMinecraftUiProfile {
            successful = this
            completeProfileDeclarations()
        }
        assertThrows(IllegalStateException::class.java) {
            checkNotNull(successful).menuBackground(image(IntSize(16, 16)))
        }

        var escaped: MinecraftUiProfileBuilder? = null
        val failure = IllegalStateException("profile callback")
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                createMinecraftUiProfile {
                    escaped = this
                    throw failure
                }
            }
        assertSame(failure, thrown)
        assertThrows(IllegalStateException::class.java) {
            checkNotNull(escaped).menuBackground(image(IntSize(16, 16)))
        }
    }

    @Test
    fun wrongThreadIsRejectedWhileBuilderCallbackIsActive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var escaped: MinecraftUiProfileBuilder? = null
        val creation =
            FutureTask {
                runCatching {
                    createMinecraftUiProfile {
                        escaped = this
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting for wrong-thread probe." }
                        completeProfileDeclarations()
                    }
                }.exceptionOrNull()
            }
        val creationThread = Thread(creation)
        creationThread.start()
        var wrongThreadRunner: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val wrongThread =
                FutureTask {
                    runCatching {
                        checkNotNull(escaped).menuBackground(image(IntSize(16, 16)))
                    }.exceptionOrNull()
                }
            wrongThreadRunner = Thread(wrongThread)
            wrongThreadRunner.start()
            assertTrue(wrongThread.get(5, TimeUnit.SECONDS) is IllegalStateException)
            release.countDown()
            assertNull(creation.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            creationThread.join(5_000)
            wrongThreadRunner?.join(5_000)
        }
    }

    @Test
    fun completeProfileCanBeReusedConcurrentlyByIndependentHosts() {
        val profile = MinecraftProfileFixture.create()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val tasks =
            List(2) {
                FutureTask {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Timed out waiting to start host." }
                    createMinecraftUiHost(
                        ScreenDefinition(UiText.Literal("reuse")) {
                            Stack(modifier = Modifier.menuBackground()) {}
                        },
                        profile,
                    ).use { host ->
                        host.attach()
                        host.frame(IntSize(16, 16)).size == IntSize(16, 16)
                    }
                }
            }
        val threads = tasks.map(::Thread)
        threads.forEach(Thread::start)
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { task -> assertTrue(task.get(5, TimeUnit.SECONDS)) }
        } finally {
            start.countDown()
            threads.forEach { thread -> thread.join(5_000) }
        }
    }

    private fun assertMissing(
        message: String,
        declaration: MinecraftUiProfileBuilder.() -> Unit,
    ) {
        assertEquals(message, assertThrows(IllegalArgumentException::class.java) { createMinecraftUiProfile(declaration) }.message)
    }

    private fun assertDeclarationRejected(
        message: String,
        afterComplete: Boolean = false,
        declaration: MinecraftUiProfileBuilder.() -> Unit,
    ) {
        createMinecraftUiProfile {
            if (afterComplete) completeProfileDeclarations()
            assertEquals(message, assertThrows(IllegalArgumentException::class.java) { declaration() }.message)
            if (afterComplete.not()) completeProfileDeclarations()
        }
    }

    private fun completeProfile(
        menu: DrawImage = image(IntSize(16, 16)),
        button: DrawImage = buttonImage(),
        border: Int = 1,
        normalBorder: Int = border,
        highlightedBorder: Int = border,
        disabledBorder: Int = border,
        glyphMask: (Int) -> DrawImage = { image(IntSize(8, 8)) },
    ): MinecraftUiProfile =
        createMinecraftUiProfile {
            completeMenuAndGlyphs(menu, glyphMask)
            buttonNormal(button, normalBorder, NineSliceCenterMode.Tiled)
            buttonHighlighted(button, highlightedBorder, NineSliceCenterMode.Tiled)
            buttonDisabled(button, disabledBorder, NineSliceCenterMode.Tiled)
        }

    private fun MinecraftUiProfileBuilder.completeMenuAndGlyphs(
        menu: DrawImage = image(IntSize(16, 16)),
        glyphMask: (Int) -> DrawImage = { image(IntSize(8, 8)) },
    ) {
        menuBackground(menu)
        completeContainerAssets()
        completeScrollAssets()
        completeTextFieldAssets()
        completeGlyphs(glyphMask)
    }

    private fun MinecraftUiProfileBuilder.completeContainerAssets(omitted: ContainerAsset? = null) {
        ContainerAsset.entries.filter { slot -> slot != omitted }.forEach { slot -> declareContainerAsset(slot) }
    }

    private fun MinecraftUiProfileBuilder.declareContainerAsset(
        slot: ContainerAsset,
        size: IntSize = slot.size,
    ) {
        val asset = image(size)
        when (slot) {
            ContainerAsset.Background -> containerBackground(asset)
            ContainerAsset.HighlightBack -> slotHighlightBack(asset)
            ContainerAsset.HighlightFront -> slotHighlightFront(asset)
        }
    }

    private fun MinecraftUiProfileBuilder.completeTextFieldAssets(omitted: TextFieldAsset? = null) {
        TextFieldAsset.entries.filter { slot -> slot !== omitted }.forEach { slot -> declareTextFieldAsset(slot) }
    }

    private fun MinecraftUiProfileBuilder.declareTextFieldAsset(
        slot: TextFieldAsset,
        size: IntSize = IntSize(200, 20),
    ) {
        val asset = image(size)
        when (slot) {
            TextFieldAsset.Normal -> textFieldNormal(asset)
            TextFieldAsset.Highlighted -> textFieldHighlighted(asset)
        }
    }

    private fun MinecraftUiProfileBuilder.completeScrollAssets(omitted: ScrollAsset? = null) {
        ScrollAsset.entries.filter { slot -> slot != omitted }.forEach { slot -> declareScrollAsset(slot) }
        checkbox(image(IntSize(20, 20)))
        checkboxHighlighted(image(IntSize(20, 20)))
        checkboxSelected(image(IntSize(20, 20)))
        checkboxSelectedHighlighted(image(IntSize(20, 20)))
        slider(image(IntSize(200, 20)))
        sliderHighlighted(image(IntSize(200, 20)))
        sliderHandle(image(IntSize(8, 20)))
        sliderHandleHighlighted(image(IntSize(8, 20)))
        loadingIndicator(image(IntSize(5, 6)))
        progressBarBorder(image(IntSize(12, 12)))
        progressBarFill(image(IntSize(6, 6)))
        progressBarFull(image(IntSize(6, 6)))
        tooltipBackground(image(IntSize(100, 100)))
        tooltipFrame(image(IntSize(100, 100)))
    }

    private fun MinecraftUiProfileBuilder.declareScrollAsset(
        slot: ScrollAsset,
        size: IntSize = slot.size,
    ) {
        val asset = image(size)
        when (slot) {
            ScrollAsset.ListBackground -> listBackground(asset)
            ScrollAsset.HeaderSeparator -> listHeaderSeparator(asset)
            ScrollAsset.FooterSeparator -> listFooterSeparator(asset)
            ScrollAsset.ScrollbarBackground -> scrollbarBackground(asset)
            ScrollAsset.ScrollbarThumb -> scrollbarThumb(asset)
        }
    }

    private fun MinecraftUiProfileBuilder.completeGlyphs(
        glyphMask: (Int) -> DrawImage = { image(IntSize(8, 8)) },
    ) {
        for (codePoint in 0x21..0x7E) {
            printableAsciiGlyph(codePoint, glyphMask(codePoint))
        }
    }

    private fun MinecraftUiProfileBuilder.completeButtons(
        button: DrawImage = buttonImage(),
        border: Int = 1,
    ) {
        buttonNormal(button, border, NineSliceCenterMode.Tiled)
        buttonHighlighted(button, border, NineSliceCenterMode.Tiled)
        buttonDisabled(button, border, NineSliceCenterMode.Tiled)
    }

    private fun MinecraftUiProfileBuilder.completeProfileDeclarations() {
        completeMenuAndGlyphs()
        val button = buttonImage()
        buttonNormal(button, 1, NineSliceCenterMode.Tiled)
        buttonHighlighted(button, 1, NineSliceCenterMode.Tiled)
        buttonDisabled(button, 1, NineSliceCenterMode.Tiled)
    }

    private enum class ScrollAsset(
        val size: IntSize,
        val label: String,
    ) {
        ListBackground(IntSize(16, 16), "List background"),
        HeaderSeparator(IntSize(32, 2), "List header separator"),
        FooterSeparator(IntSize(32, 2), "List footer separator"),
        ScrollbarBackground(IntSize(6, 32), "Scrollbar background"),
        ScrollbarThumb(IntSize(6, 32), "Scrollbar thumb"),
    }

    private enum class ContainerAsset(
        val size: IntSize,
        val label: String,
    ) {
        Background(IntSize(256, 256), "Container background"),
        HighlightBack(IntSize(24, 24), "Slot back highlight"),
        HighlightFront(IntSize(24, 24), "Slot front highlight"),
    }

    private enum class TextFieldAsset(
        val label: String,
    ) {
        Normal("Normal TextField sprite"),
        Highlighted("Highlighted TextField sprite"),
    }

    private fun buttonImage(): DrawImage = image(IntSize(200, 20))

    private fun maskAt(x: Int): DrawImage =
        createDrawImage(
            IntSize(8, 8),
            IntArray(64) { index -> if (index == x) -1 else 0x00FFFFFF },
        )

    private fun glyph(
        profile: MinecraftUiProfile,
        codePoint: Int,
    ): MinecraftGlyphSnapshot {
        val field = profile.javaClass.getDeclaredField("glyphs")
        field.isAccessible = true
        val glyphs = field.get(profile)
        check(glyphs is Map<*, *>) { "Profile glyph storage must remain a map." }
        return checkNotNull(glyphs[codePoint] as? MinecraftGlyphSnapshot)
    }

    private fun button(
        profile: MinecraftUiProfile,
        fieldName: String,
    ): MinecraftButtonSpriteSnapshot {
        val field = profile.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return checkNotNull(field.get(profile) as? MinecraftButtonSpriteSnapshot)
    }

    private fun image(size: IntSize): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { 0x00FFFFFF })
}
