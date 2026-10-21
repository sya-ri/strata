package dev.s7a.strata.integration.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy

/**
 * Shared declarations and caller-owned state for browser and portable interaction acceptance.
 */
@Suppress("unused") // The real browser acceptance entry point is in jsMain, outside Qodana's JVM usage graph.
internal class InteractionScenario {
    val field = TextFieldState(maxLength = 128)
    val area = TextAreaState(maxLength = 1000)
    val scroll = ScrollState()
    val list = VirtualListState<Int>()
    val navigation = PanZoomState(initialZoom = 2.0, maximumZoom = 4096.0)
    val revision = mutableStateOf(0)
    val actions = mutableStateOf(0)
    val frame = createDrawImage(IntSize(3, 3), IntArray(9) { if (it == 4) 0xffeeeeee.toInt() else 0xff777777.toInt() })
    val skin = createDrawImage(IntSize(64, 64), IntArray(4096) { index -> if (index % 64 in 40..47 && index / 64 in 8..15) 0x8000ff00.toInt() else 0xffff6600.toInt() })
    private val appearance = TextInputAppearance.Custom(ImageSource.Pixels(frame), ImageSource.Pixels(frame), ArgbColor(0xff111111.toInt()))
    private var subscriptions = 0
    val activeSubscriptions: Int get() = subscriptions
    private val tile = createDrawImage(IntSize(16, 16), IntArray(256) { index -> if ((index % 16 / 4 + index / 16 / 4) % 2 == 0) 0xff6699cc.toInt() else 0xff334455.toInt() })
    private val source =
        object : TiledImageSource {
            override val bounds = LongRect(0, 0, 64, 64)
            override val levels = listOf(TiledImageLevel(IntSize(16, 16), 1))

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
                StateSource {
                    subscriptions += 1
                    StateSubscription(StateSnapshot(StateRevision(0), TiledImageTile.Ready(tile))) { subscriptions -= 1 }
                }
        }

    /**
     * Produces the same application tree independently for each runtime.
     */
    fun definition(): UiDefinition =
        UiDefinition(inputPolicy = UiInputPolicy.Movement) {
            Column(spacing = 8) {
                Text("Revision ${revision.value}")
                TextField(field, appearance, IntSize(260, 30), textStyle = TextStyle.ContainerLabel, key = ElementKey("field"))
                TextArea(area, appearance, TextAreaViewport.Size(IntSize(260, 70)), textStyle = TextStyle.ContainerLabel, key = ElementKey("area"))
                Row(spacing = 8) {
                    ScrollArea(scroll, Modifier.size(130, 100)) {
                        Column { for (index in 0 until 30) Text("Scroll $index") }
                    }
                    TiledImage(source, navigation, IntSize(120, 100), modifier = Modifier.panZoom(navigation)) {
                        PlayerHead(PlayerSkinSource.Pixels(skin), scale = PlayerHeadScale(1), modifier = Modifier.atContentPosition(DoubleOffset(32.0, 32.0)))
                    }
                }
                Row(spacing = 8) {
                    Image(ImageSource.Resource(imageId), IntSize(18, 18))
                    Stack(Modifier.size(24, 18).imageBackground(ImageSource.Pixels(frame), Insets.all(1))) { Text("A") }
                    Text(UiText.WithFont(UiText.Literal("A日🙂"), fontId))
                }
                VirtualList(10_000, { it }, { it }, list, IntSize(260, 80), 24) { Text("Row $it", modifier = Modifier.onActivate { actions.value += 1 }) }
                Stack(
                    Modifier
                        .size(120, 30)
                        .background(ArgbColor(0xff777777.toInt()))
                        .semantics(Semantics(UiText.Literal("Action surface"), SemanticsRole.Button))
                        .onActivate { actions.value += 1 },
                ) {
                    Text("Action ${actions.value}")
                }
            }
        }

    /**
     * Publishes an independent update while editors and navigation retain their state.
     */
    fun advance() {
        revision.value += 1
    }

    /**
     * Resource identities and viewport shared by independent runtime fixtures.
     */
    companion object {
        val viewport = IntSize(300, 480)
        val imageId = ResourceId("strata-test", "textures/frame.png")
        val fontId = ResourceId("strata-test", "font")
    }
}
