package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies asynchronous skin cutoff, fallback transitions and terminal source release.
 */
internal class WebSkinSourceTest {
    @Test
    fun sourceUpdatesCommitAtFramesAndCloseReleasesTheSubscription() {
        val source = Source()
        val root = document.createElement("div") as HTMLElement
        val host =
            mountWeb(
                UiDefinition {
                    Stack {
                        PlayerHead(
                            PlayerSkinSource.Name("example"),
                            PlayerHeadScale(1),
                            loadingContent = { Text("Loading") },
                            failureContent = { Text("Failed") },
                        )
                    }
                },
                root,
                IntSize(100, 50),
                WebUiResources(skins = { source }),
            )
        assertEquals("Loading", root.textContent)
        val skin = createDrawImage(IntSize(64, 64), IntArray(4096) { 0xff112233.toInt() })
        source.publish(WebPlayerSkin.Ready(skin))
        assertEquals("Loading", root.textContent)
        host.render(IntSize(100, 50))
        assertEquals(2, root.querySelectorAll("canvas").length)
        source.publish(WebPlayerSkin.Failed)
        host.render(IntSize(100, 50))
        assertEquals("Failed", root.textContent)
        host.close()
        assertEquals(0, source.subscribers)
        assertFalse(root.hasChildNodes())
    }

    private class Source : StateSource<WebPlayerSkin> {
        private val observers = LinkedHashSet<(StateSnapshot<WebPlayerSkin>) -> Unit>()
        private var current = StateSnapshot<WebPlayerSkin>(StateRevision(0), WebPlayerSkin.Loading)
        val subscribers: Int get() = observers.size

        override fun subscribe(observer: (StateSnapshot<WebPlayerSkin>) -> Unit): StateSubscription<WebPlayerSkin> {
            observers.add(observer)
            return StateSubscription(current) { observers.remove(observer) }
        }

        fun publish(value: WebPlayerSkin) {
            current = StateSnapshot(StateRevision(current.revision.value + 1), value)
            observers.toList().forEach { it(current) }
        }
    }
}
