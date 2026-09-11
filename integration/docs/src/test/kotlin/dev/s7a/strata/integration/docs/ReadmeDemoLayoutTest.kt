package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.integration.docs.example.ReadmeDemoColors
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Verifies natural sizing, fixed row widths, and text-only alignment using real compiled screen geometry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ReadmeDemoLayoutTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun addingContentExpandsNaturalRowsAndRepositionsText() {
        val assets = ReadmeDemoFixture.assets(temporary)
        val basic = frame(ReadmeDemoStage.Basic, assets)
        val roles = frame(ReadmeDemoStage.Roles, assets)
        val actions = frame(ReadmeDemoStage.Actions, assets)
        val basicRows = rows(basic)
        val roleRows = rows(roles)
        val actionRows = rows(actions)
        assertEquals(4, basic.semantics.count { it.semantics.role == SemanticsRole.Text })
        assertEquals(7, roles.semantics.count { it.semantics.role == SemanticsRole.Text })
        assertEquals(3, actions.semantics.count { it.semantics.role == SemanticsRole.Button })
        assertEquals(0, roles.semantics.count { it.semantics.role == SemanticsRole.Button })
        assertTrue(1 < actionRows.map { it.width }.distinct().size)
        basicRows.indices.forEach { index ->
            assertTrue(basicRows[index].width < roleRows[index].width)
            assertEquals(36, basicRows[index].height)
            assertEquals(basicRows[index].height, roleRows[index].height)
            assertEquals(basicRows[index].top, roleRows[index].top)
            val name = UiText.Literal(assets.players[index].name)
            val basicName = basic.semantics.single { it.semantics.label == name }.bounds
            val roleName = roles.semantics.single { it.semantics.label == name }.bounds
            assertTrue(roleName.top < basicName.top, "Adding a role must recenter the text beside the larger face.")
            assertEquals(basicName.left, roleName.left)
            assertTrue(roleRows[index].width < actionRows[index].width)
            assertEquals(roleRows[index].height, actionRows[index].height)
        }
    }

    @Test
    fun fixedRowsKeepFramesFacesAndButtonsStillWhenOnlyTextAlignmentChanges() {
        val assets = ReadmeDemoFixture.assets(temporary)
        val natural = rows(frame(ReadmeDemoStage.Actions, assets))
        val fixed = frame(ReadmeDemoStage.Fixed, assets)
        val right = frame(ReadmeDemoStage.Right, assets)
        val left = frame(ReadmeDemoStage.Left, assets)
        val fixedRows = rows(fixed)
        assertEquals(List(3) { 220 }, fixedRows.map { it.width })
        natural.indices.forEach { index ->
            assertTrue(natural[index].width < fixedRows[index].width)
            assertEquals(natural[index].top, fixedRows[index].top)
            assertEquals(natural[index].height, fixedRows[index].height)
        }
        listOf(right, left).forEach { aligned ->
            assertEquals(fixedRows, rows(aligned), "Row frames must not move during text alignment.")
            assertEquals(buttons(fixed), buttons(aligned), "Buttons must stay at their fixed positions.")
            val skins = assets.players.map { it.skin.skin }
            val fixedHeads = fixed.drawCommands.filterIsInstance<DrawCommand.SampledImage>().filter { it.image in skins }
            val alignedHeads = aligned.drawCommands.filterIsInstance<DrawCommand.SampledImage>().filter { it.image in skins }
            assertEquals(6, fixedHeads.size)
            assertEquals(fixedHeads, alignedHeads, "Face and hat layers must remain fixed.")
        }
        val labels = assets.players.flatMap { listOf(UiText.Literal(it.name), UiText.Literal(it.role)) }.toSet()
        val fixedText = fixed.semantics.filter { it.semantics.label in labels }
        val rightText = right.semantics.filter { it.semantics.label in labels }
        val leftText = left.semantics.filter { it.semantics.label in labels }
        assertEquals(6, fixedText.size)
        fixedText.indices.forEach { index ->
            assertEquals(fixedText[index].bounds.size, rightText[index].bounds.size)
            assertEquals(fixedText[index].bounds.top, rightText[index].bounds.top)
            assertTrue(fixedText[index].bounds.left < rightText[index].bounds.left, "Only the text must visibly move right.")
        }
        rightText.chunked(2).forEach { pair -> assertEquals(pair[0].bounds.right, pair[1].bounds.right) }
        leftText.chunked(2).forEach { pair -> assertEquals(pair[0].bounds.left, pair[1].bounds.left) }
        assertEquals(fixedText, leftText)
        ReadmeDemoStage.entries.take(6).forEach { stage ->
            val current = frame(stage, assets)
            val bounds = rows(current)
            assertEquals(3, bounds.size)
            bounds.zipWithNext().forEach { (first, second) -> assertEquals(6, second.top - first.bottom) }
            current.semantics.forEach { entry ->
                val box = entry.bounds
                assertTrue(0 <= box.left && box.right <= 256 && 0 <= box.top && box.bottom <= 192)
            }
        }
    }

    @Test
    fun growingRowsOverflowBeforeScrollingAndTheAddedBarTracksWheelInput() {
        val assets = ReadmeDemoFixture.assets(temporary)
        val baseline = rows(frame(ReadmeDemoStage.Left, assets))
        val arrivals = listOf(ReadmeDemoStage.Four, ReadmeDemoStage.Five, ReadmeDemoStage.Six, ReadmeDemoStage.Seven, ReadmeDemoStage.Eight)
        arrivals.forEachIndexed { index, stage ->
            val current = frame(stage, assets)
            assertEquals(index + 4, rows(current).size)
            assertEquals(baseline, rows(current).take(3), "Adding players must preserve existing row geometry.")
            assertTrue(current.drawCommands.none { it is DrawCommand.PushClip }, "Overflow precedes adding ScrollArea.")
        }
        assertTrue(192 < rows(frame(ReadmeDemoStage.Eight, assets)).last().bottom)
        val area = wheelFrame(ReadmeDemoStage.Area, assets, 208.0)
        assertTrue(thumb(area, assets).isEmpty(), "The area scrolls before any scrollbar is added.")
        assertVisibleLastRow(area)
        createMinecraftUiHost(
            ReadmeDemoStage.Bar.create(assets.players, assets.panel),
            assets.minecraft.profile,
            LwjglMinecraftFontBackendFactory,
        ).use { host ->
            host.attach()
            val initial = host.frame(IntSize(256, 192), FrameTime(0L))
            val initialTop = rows(initial).first().top
            val initialThumbTop = thumb(initial, assets).minOf { it.top }
            var previousOffset = 0.0
            listOf(42.0, 84.0, 208.0, 0.0).forEach { offset ->
                val event = PointerEvent.Scroll(IntOffset(40, 80), 0.0, (offset - previousOffset) / 9.0)
                assertEquals(InputResult.Consumed, host.dispatchPointer(event))
                val current = host.frame(IntSize(256, 192), FrameTime(0L))
                assertEquals(initialTop - offset.toInt(), rows(current).first().top)
                val thumbTop = thumb(current, assets).minOf { it.top }
                if (offset == 0.0) {
                    assertEquals(initialThumbTop, thumbTop)
                } else {
                    assertTrue(initialThumbTop < thumbTop, "The independently added thumb must follow the list.")
                }
                previousOffset = offset
            }
        }
        val bottom = wheelFrame(ReadmeDemoStage.Bar, assets, 208.0)
        assertVisibleLastRow(bottom)
        val viewport =
            bottom.drawCommands
                .filterIsInstance<DrawCommand.PushClip>()
                .single()
                .bounds
        assertEquals(viewport.bottom, thumb(bottom, assets).maxOf { it.bottom })
    }

    private fun assertVisibleLastRow(frame: RuntimeUiFrame) {
        val viewport =
            frame.drawCommands
                .filterIsInstance<DrawCommand.PushClip>()
                .single()
                .bounds
        val lastRow = rows(frame).last()
        assertTrue(viewport.top <= lastRow.top && lastRow.bottom <= viewport.bottom)
        assertEquals(126, viewport.height)
        assertTrue(0 <= viewport.left && viewport.right <= 256 && 0 <= viewport.top && viewport.bottom <= 192)
    }

    private fun wheelFrame(
        stage: ReadmeDemoStage,
        assets: ReadmeDemoAssets,
        offset: Double,
    ): RuntimeUiFrame =
        createMinecraftUiHost(stage.create(assets.players, assets.panel), assets.minecraft.profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(IntSize(256, 192), FrameTime(0L))
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(40, 80), 0.0, offset / 9.0)))
            host.frame(IntSize(256, 192), FrameTime(0L))
        }

    private fun thumb(
        frame: RuntimeUiFrame,
        assets: ReadmeDemoAssets,
    ): List<IntRect> =
        frame.drawCommands
            .filterIsInstance<DrawCommand.BlitImage>()
            .filter { it.image == assets.minecraft.image(ShowcaseGuiAsset.ScrollbarThumb.id) }
            .map { it.destination }

    private fun frame(
        stage: ReadmeDemoStage,
        assets: ReadmeDemoAssets,
    ): RuntimeUiFrame =
        createMinecraftUiHost(stage.create(assets.players.take(stage.playerCount), assets.panel), assets.minecraft.profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(IntSize(256, 192), FrameTime(0L))
        }

    private fun buttons(frame: RuntimeUiFrame): List<SemanticsEntry> = frame.semantics.filter { it.semantics.role == SemanticsRole.Button }

    private fun rows(frame: RuntimeUiFrame): List<IntRect> =
        frame.drawCommands
            .filterIsInstance<DrawCommand.FillRectangle>()
            .filter { it.color == ReadmeDemoColors.row }
            .map { it.bounds }
}
