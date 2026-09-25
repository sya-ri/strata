@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.LoadingIndicator
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.SelectionList
import dev.s7a.strata.component.SelectionListState
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Tab
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.onCheckedChange
import dev.s7a.strata.modifier.onCycle
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onSelectionChange
import dev.s7a.strata.modifier.onSliderChange
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.modifier.size
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Uses real Minecraft profile nodes and encoded messages to compare local and remote pixels and input behavior.
 */
internal class MinecraftRemoteScreenTest {
    @Test
    fun standardNotificationsKeepClientFocusAndServerCallbacks() {
        val focus = mutableListOf<FocusEvent>()
        val hover = mutableListOf<PointerHoverEvent>()
        var released = 0
        Pairing {
            Spacer(
                Modifier.Empty
                    .size(30, 30)
                    .onHover({ value -> hover.add(value) })
                    .onFocusChanged({ value -> focus.add(value) })
                    .onRelease { released++ },
            )
        }.use { pair ->
            pair.host.dispatchPointer(PointerEvent.Move(IntOffset(10, 10)))
            pair.click(10, 10)
            pair.host.dispatchPointer(PointerEvent.Release(IntOffset(10, 10), PointerButton.Primary))
            pair.synchronize()
            assertEquals(listOf(FocusEvent.Gained), focus)
            assertEquals(listOf(PointerHoverEvent.Enter), hover)
            assertEquals(1, released)
            pair.host.dispatchPointer(PointerEvent.Move(IntOffset(50, 50)))
            pair.synchronize()
            assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit), hover)
        }
    }

    @Test
    fun selectionListRetainsServerModelAndDispatchesTypedSelection() {
        val state = SelectionListState<Int>()
        val selected = mutableListOf<Int>()
        Pairing {
            SelectionList((0 until 100).toList(), { it }, state, IntSize(80, 60), 20, modifier = Modifier.Empty.onSelectionChange<Int>({ value -> selected.add(value) })) {
                Text("Row $it")
            }
        }.use { pair ->
            pair.click(10, 25)
            pair.synchronize()
            assertEquals(1, state.selectedKey)
            assertEquals(listOf(1), selected)
            assertTrue(
                pair.host
                    .frame(SIZE)
                    .semantics
                    .any { it.semantics.selected == true },
            )
        }
    }

    @Test
    fun tiledImagesPreservePixelsPanZoomWorkingSetAndRelease() {
        val source = Tiles()
        val state = PanZoomState()
        Pairing { tileContent(source, state) }.use { pair ->
            assertTilesMatch(pair, source, state)
            pair.host.dispatchPointer(PointerEvent.Scroll(IntOffset(48, 48), 0.0, -6.0))
            pair.synchronize()
            assertTrue(1.0 < state.metrics.zoom)
            assertTilesMatch(pair, source, state)
            val before = state.metrics.center
            pair.click(48, 48)
            pair.host.dispatchPointer(PointerEvent.Drag(IntOffset(20, 48), PointerButton.Primary, -28.0, 0.0))
            pair.host.dispatchPointer(PointerEvent.Release(IntOffset(20, 48), PointerButton.Primary))
            pair.synchronize()
            assertTrue(before.x < state.metrics.center.x)
            assertTilesMatch(pair, source, state)
            source.publish(0xFF00FF00.toInt())
            pair.synchronize()
            assertTilesMatch(pair, source, state)
            assertTrue(source.observers.size <= 16)
        }
        assertEquals(0, source.observers.size)
    }

    private fun assertTilesMatch(
        pair: Pairing,
        source: Tiles,
        state: PanZoomState,
    ) {
        val localState = PanZoomState(state.metrics.center, state.metrics.zoom)
        createMinecraftUiHost(ScreenDefinition("Tiles") { Stack { tileContent(source, localState) } }, pair.profile).use { local ->
            local.attach()
            val expected = rasterizeHeadless(local.frame(SIZE).drawCommands, SIZE).copyArgb()
            val actual = rasterizeHeadless(pair.host.frame(SIZE).drawCommands, SIZE).copyArgb()
            assertArrayEquals(expected, actual)
        }
    }

    private fun UiScope.tileContent(
        source: Tiles,
        state: PanZoomState,
    ) {
        TiledImage(source, state, IntSize(96, 96), cachePolicy = TiledImageCachePolicy(16, 16384), modifier = Modifier.Empty.panZoom(state)) {
            Text("Pin", modifier = Modifier.Empty.atContentPosition(DoubleOffset(32.0, 32.0)))
        }
    }

    /**
     * Revisioned tile fixture recording live subscriptions without retaining retired pixel snapshots.
     */
    private class Tiles : TiledImageSource {
        override val bounds = LongRect(0, 0, 64, 64)
        override val levels = listOf(TiledImageLevel(IntSize(16, 16), 1))
        val observers = mutableMapOf<Any, Pair<TiledImageTileId, (StateSnapshot<TiledImageTile>) -> Unit>>()
        private var revision = 0L
        private var color = 0xFF550000.toInt()

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
            StateSource { observer ->
                val token = Any()
                observers[token] = id to observer
                StateSubscription(snapshot(id)) { observers.remove(token) }
            }

        fun publish(next: Int) {
            color = next
            revision++
            observers.values.toList().forEach { (id, observer) -> observer(snapshot(id)) }
        }

        private fun snapshot(id: TiledImageTileId): StateSnapshot<TiledImageTile> = StateSnapshot(StateRevision(revision), TiledImageTile.Ready(createDrawImage(IntSize(16, 16), IntArray(256) { color or (id.column * 16L + id.row).toInt() })))
    }

    @Test
    fun standardProfilePrimitivesRenderIdenticalPixels() {
        val checked = CheckboxState(true)
        val cycle = CycleButtonState(listOf("A", "B"))
        val slider = SliderState(0.4)
        val field = TextFieldState("A")
        val area = TextAreaState("A\nB")
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, -65536, -16711936, -16776961))
        val canvas = canvasSource(image)
        val scenarios: List<UiScope.() -> Unit> =
            listOf(
                { Text("Hello") },
                { Button("Press") },
                { Tab("Selected", true) },
                { ProgressBar(0.4) },
                { LoadingIndicator() },
                { Image(ImageSource.Pixels(image)) },
                { Canvas(canvas, IntSize(8, 8)) },
                { Slot() },
                { Checkbox("Checked", checked) },
                { CycleButton(cycle) },
                { Slider("Amount", slider) },
                { TextField(field, IntSize(100, 20)) },
                { TextArea(area, TextAreaViewport.Lines(100, 4)) },
            )
        scenarios.forEachIndexed { index, content ->
            Pairing(content).use { pair ->
                createMinecraftUiHost(ScreenDefinition("Local") { Stack(content = content) }, pair.profile).use { local ->
                    local.attach()
                    val expected = rasterizeHeadless(local.frame(SIZE).drawCommands, SIZE).copyArgb()
                    val actual = rasterizeHeadless(pair.host.frame(SIZE).drawCommands, SIZE).copyArgb()
                    assertArrayEquals(expected, actual, "Profile scenario $index")
                }
            }
        }
    }

    @Test
    fun checkboxChangesAuthoritativeStateAndCallsBusinessHandlerExactlyOnce() {
        val state = CheckboxState()
        val received = mutableListOf<Boolean>()
        Pairing { Checkbox("Check", state, modifier = Modifier.Empty.onCheckedChange({ value -> received.add(value) })) }.use { pair ->
            pair.click(2, 2)
            assertFalse(state.checked)
            val action = pair.incoming.filterIsInstance<RemoteMessage.Action>().single()
            pair.server.receive(action)
            pair.server.receive(action)
            pair.incoming.clear()
            pair.synchronize()
            assertEquals(true, state.checked)
            assertEquals(listOf(true), received)
            assertEquals(
                true,
                pair.host
                    .frame(SIZE)
                    .semantics
                    .single()
                    .semantics.checked,
            )
        }
    }

    @Test
    fun typedCycleModelNeverCrossesTheWire() {
        val first = Choice("First", Any())
        val second = Choice("Second", Any())
        val state = CycleButtonState(listOf(first, second))
        val selected = mutableListOf<Choice>()
        Pairing { CycleButton(state, modifier = Modifier.Empty.onCycle<Choice>({ value -> selected.add(value) }), label = { UiText.Literal(it.label) }) }.use { pair ->
            pair.click(2, 2)
            pair.synchronize()
            assertEquals(second, state.value)
            assertEquals(listOf(second), selected)
        }
    }

    @Test
    fun sliderDragUsesLocalCaptureAndValidatedServerValues() {
        val state = SliderState(0.0)
        val changes = mutableListOf<Double>()
        Pairing { Slider("Amount", state, width = 100, modifier = Modifier.Empty.onSliderChange({ value -> changes.add(value) })) }.use { pair ->
            pair.click(10, 10)
            pair.host.dispatchPointer(PointerEvent.Drag(IntOffset(90, 10), PointerButton.Primary, 80.0, 0.0))
            pair.host.dispatchPointer(PointerEvent.Release(IntOffset(90, 10), PointerButton.Primary))
            pair.synchronize()
            assertEquals(changes.last(), state.value)
            assertEquals(true, 0.8 < state.value)
        }
    }

    @Test
    fun textEditsPrecedeButtonActionsAndOldResponsesPreserveNewerText() {
        val state = TextFieldState()
        val submitted = mutableListOf<String>()
        Pairing {
            Column {
                TextField(state, IntSize(100, 20))
                Button("Submit", modifier = Modifier.Empty.onActivate { submitted.add(state.value) })
            }
        }.use { pair ->
            pair.click(5, 5)
            pair.host.dispatchTextInput(TextInputEvent.Character('A'.code))
            pair.client.flushEdits()
            val first =
                pair.incoming
                    .filterIsInstance<RemoteMessage.Action>()
                    .first()
                    .also(pair.incoming::remove)
            pair.server.receive(first)
            pair.server.tick()
            pair.host.dispatchTextInput(TextInputEvent.Character('B'.code))
            pair.deliver()
            pair.click(5, 25)
            pair.synchronize()
            assertEquals("AB", state.value)
            assertEquals(listOf("AB"), submitted)
        }
    }

    @Test
    fun scrollbarBeforeEditorSharesTheEditorOwnedPosition() {
        val state = TextAreaState((1..50).joinToString("\n") { "Line" })
        Pairing {
            Row {
                Scrollbar(state.scrollState, Modifier.Empty.height(40))
                TextArea(state, TextAreaViewport.Lines(100, 3))
            }
        }.use { pair ->
            pair.click(2, 5)
            pair.host.dispatchPointer(PointerEvent.Drag(IntOffset(2, 35), PointerButton.Primary, 0.0, 30.0))
            pair.host.dispatchPointer(PointerEvent.Release(IntOffset(2, 35), PointerButton.Primary))
            pair.synchronize()
            assertEquals(true, 0.0 < state.scrollState.metrics.offset)
            assertNotNull(pair.host.frame(SIZE))
        }
    }

    @Test
    fun virtualListTransfersOnlyVisibleRowsAndUsesLocalScrolling() {
        val state = VirtualListState<Int>()
        val evaluated = mutableListOf<Int>()
        Pairing {
            VirtualList(100000, { it }, { it }, state, IntSize(100, 60), 12) { index ->
                evaluated.add(index)
                Text("Row $index")
            }
        }.use { pair ->
            assertEquals((0..5).toList(), evaluated)
            pair.host.dispatchPointer(PointerEvent.Scroll(IntOffset(5, 5), 0.0, 24.0))
            assertEquals(0.0, state.scrollState.metrics.offset)
            pair.synchronize()
            assertEquals(240.0, state.scrollState.metrics.offset)
            assertEquals((19..25).toList(), evaluated.drop(6))
            createMinecraftUiHost(
                ScreenDefinition("Local") {
                    Stack {
                        VirtualList(100000, { it }, { it }, VirtualListState(initialIndex = 20), IntSize(100, 60), 12) { Text("Row $it") }
                    }
                },
                pair.profile,
            ).use { local ->
                local.attach()
                assertArrayEquals(
                    rasterizeHeadless(local.frame(SIZE).drawCommands, SIZE).copyArgb(),
                    rasterizeHeadless(pair.host.frame(SIZE).drawCommands, SIZE).copyArgb(),
                )
            }
        }
    }

    private data class Choice(
        val label: String,
        val privateModel: Any,
    )

    /**
     * Owns both endpoints and their ordinary retained client host for one deterministic connection.
     */
    private class Pairing(
        content: UiScope.() -> Unit,
    ) : AutoCloseable {
        val profile = MinecraftProfileFixture.create()
        val incoming = mutableListOf<RemoteMessage>()
        private val outgoing = mutableListOf<RemoteMessage>()
        private val codec = RemoteMessageCodec()
        private val registry = RemoteRegistry().also(RemoteBuiltins::register)
        private val runtime = RemoteComponentRuntime()
        val server = RemoteServerSession(1, ProjectionValue.Text("Test"), registry.types, send = { outgoing.add(codec.decode(codec.encode(it))) }) { runtime.evaluate { Stack(content = content) } }
        val client: RemoteClientSession
        val host: MinecraftUiHost

        init {
            server.tick()
            client = RemoteClientSession(outgoing.removeAt(0) as RemoteMessage.Snapshot, registry, send = { incoming.add(codec.decode(codec.encode(it))) })
            host = createMinecraftUiHost(client.definition(UiText.Literal("Remote")), profile)
            host.attach()
            host.frame(SIZE)
        }

        fun click(
            x: Int,
            y: Int,
        ) {
            host.dispatchPointer(PointerEvent.Press(IntOffset(x, y), PointerButton.Primary))
        }

        fun synchronize() {
            client.flushEdits()
            val actions = incoming.toList()
            incoming.clear()
            actions.forEach(server::receive)
            server.tick()
            deliver()
        }

        fun deliver() {
            val responses = outgoing.toList()
            outgoing.clear()
            responses.forEach {
                when (it) {
                    is RemoteMessage.Update -> client.receive(it)
                    is RemoteMessage.Acknowledgement -> client.receive(it)
                    else -> error("Unexpected server message: $it")
                }
            }
        }

        override fun close() {
            host.close()
            client.close()
            server.close()
        }
    }

    private companion object {
        val SIZE = IntSize(200, 200)
    }
}
