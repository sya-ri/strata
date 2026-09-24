package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.integration.docs.example.ReadmeDemoChrome
import dev.s7a.strata.layout.HorizontalAlignment.Start
import dev.s7a.strata.layout.VerticalAlignment.Center
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.width
import dev.s7a.strata.render.ArgbColor
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
 * Verifies natural sizing, parent-controlled row widths, and fixed rosters and linked scrolling using real compiled screen geometry.
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
        assertEquals(9, basic.semantics.count { it.semantics.role == SemanticsRole.Text })
        assertEquals(17, roles.semantics.count { it.semantics.role == SemanticsRole.Text })
        assertEquals(8, actions.semantics.count { it.semantics.role == SemanticsRole.Button })
        assertEquals(0, roles.semantics.count { it.semantics.role == SemanticsRole.Button })
        assertTrue(1 < actionRows.map { it.width }.distinct().size)
        assertTrue(basicRows.zip(roleRows).any { (basicRow, roleRow) -> basicRow.width < roleRow.width })
        basicRows.indices.forEach { index ->
            assertTrue(basicRows[index].width <= roleRows[index].width)
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
    fun everyStageKeepsEightPlayersAndLeftAlignedText() {
        val assets = ReadmeDemoFixture.assets(temporary)
        val names = assets.players.map { UiText.Literal(it.name) }.toSet()
        ReadmeDemoStage.entries.forEach { stage ->
            val current = frame(stage, assets)
            val bounds = rows(current)
            assertEquals(8, bounds.size)
            assertEquals(1, current.semantics.count { it.semantics.label == UiText.Literal("Players (8)") })
            assertEquals(
                names,
                current.semantics
                    .filter { it.semantics.label in names }
                    .map { it.semantics.label }
                    .toSet(),
            )
            bounds.zipWithNext().forEach { (first, second) -> assertEquals(6, second.top - first.bottom) }
            assets.players.forEach { player ->
                val name = current.semantics.single { it.semantics.label == UiText.Literal(player.name) }
                val role = current.semantics.singleOrNull { it.semantics.label == UiText.Literal(player.role) }
                if (role != null) {
                    assertEquals(name.bounds.left, role.bounds.left)
                }
            }
        }
    }

    @Test
    fun fixingListWidthPrecedesExpandingTheTextSpace() {
        val assets = ReadmeDemoFixture.assets(temporary)
        val natural = frame(ReadmeDemoStage.Actions, assets)
        val fixed = frame(ReadmeDemoStage.Fixed, assets)
        val weighted = frame(ReadmeDemoStage.Weighted, assets)
        assertEquals(List(8) { 220 }, rows(fixed).map { it.width })
        assertEquals(rows(fixed), rows(weighted), "Adding weight must preserve the already fixed row frames.")
        assertEquals(buttons(natural), buttons(fixed), "Fixing row widths must precede moving their buttons.")
        buttons(fixed).zip(buttons(weighted)).forEach { (before, after) ->
            assertTrue(before.bounds.left < after.bounds.left, "The separate weight edit must visibly move each button.")
            assertEquals(before.bounds.top, after.bounds.top)
        }
        assertEquals(1, buttons(weighted).map { it.bounds.right }.distinct().size)
    }

    @Test
    fun outerColumnWidthControlsEveryRowWithoutChangingTheRowDefinition() {
        val assets = ReadmeDemoFixture.assets(temporary)
        val frames =
            listOf(180, 220).map { width ->
                val screen =
                    ReadmeDemoChrome.screen(assets.panel, playerCount = assets.players.size) {
                        Column(Modifier.Empty.width(width), spacing = 6) {
                            assets.players.forEach { player ->
                                Row(
                                    modifier =
                                        Modifier.Empty
                                            .background(ArgbColor(0xFF4A4A4A.toInt()))
                                            .padding(6)
                                            .fillMaxWidth(),
                                    spacing = 8,
                                    verticalAlignment = Center,
                                ) {
                                    PlayerHead(player.skin, PlayerHeadScale(3))
                                    Column(
                                        modifier = Modifier.Empty.weight(1f),
                                        spacing = 4,
                                        horizontalAlignment = Start,
                                    ) {
                                        Text(player.name)
                                        Text(player.role)
                                    }
                                    Button("Invite", width = 60)
                                }
                            }
                        }
                    }
                createMinecraftUiHost(screen, assets.minecraft.profile, LwjglMinecraftFontBackendFactory).use { host ->
                    host.attach()
                    host.frame(IntSize(256, 192), FrameTime(0L))
                }
            }
        assertEquals(List(8) { 180 }, rows(frames[0]).map { it.width })
        assertEquals(List(8) { 220 }, rows(frames[1]).map { it.width })
        rows(frames[0]).zip(rows(frames[1])).forEach { (narrow, wide) ->
            assertEquals(narrow.left, wide.left)
            assertEquals(narrow.top, wide.top)
            assertEquals(narrow.height, wide.height)
        }
        buttons(frames[0]).zip(buttons(frames[1])).forEach { (narrow, wide) ->
            assertEquals(narrow.bounds.size, wide.bounds.size)
            assertEquals(narrow.bounds.top, wide.bounds.top)
            assertEquals(40, wide.bounds.left - narrow.bounds.left)
        }
    }

    @Test
    fun fixedRosterOverflowsBeforeScrollingAndTheAddedBarTracksWheelInput() {
        val assets = ReadmeDemoFixture.assets(temporary)
        listOf(ReadmeDemoStage.Basic, ReadmeDemoStage.Roles, ReadmeDemoStage.Actions, ReadmeDemoStage.Fixed, ReadmeDemoStage.Weighted).forEach { stage ->
            val current = frame(stage, assets)
            assertEquals(8, rows(current).size)
            assertTrue(current.drawCommands.none { it is DrawCommand.PushClip }, "Overflow precedes adding ScrollArea.")
            assertTrue(192 < rows(current).last().bottom)
        }
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
        createMinecraftUiHost(stage.create(assets.players, assets.panel), assets.minecraft.profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(IntSize(256, 192), FrameTime(0L))
        }

    private fun buttons(frame: RuntimeUiFrame): List<SemanticsEntry> = frame.semantics.filter { it.semantics.role == SemanticsRole.Button }

    private fun rows(frame: RuntimeUiFrame): List<IntRect> =
        frame.drawCommands
            .filterIsInstance<DrawCommand.FillRectangle>()
            .filter { it.color == ArgbColor(0xFF4A4A4A.toInt()) }
            .map { it.bounds }
}
