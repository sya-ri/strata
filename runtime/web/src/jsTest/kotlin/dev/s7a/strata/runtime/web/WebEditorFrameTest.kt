package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies editor frame encoding independently of retained text and native editor identity.
 */
internal class WebEditorFrameTest {
    @Test
    fun sharedFramePixelsAreEncodedOnceAcrossSiblingUpdatesAndResize() {
        val image = createDrawImage(IntSize(3, 3), IntArray(9) { 0xff224466.toInt() })
        val source = ImageSource.Pixels(image)
        val appearance = TextInputAppearance.Custom(source, source, ArgbColor(-1))
        val revision = mutableStateOf(0)
        val field = TextFieldState("retained")
        val area = TextAreaState("multiline")
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        CanvasEncodingProbe().use { probe ->
            val host =
                mountWeb(
                    UiDefinition {
                        Column {
                            Text("Revision ${revision.value}")
                            TextField(field, appearance, IntSize(160, 30), key = ElementKey("field"))
                            TextArea(area, appearance, TextAreaViewport.Size(IntSize(160, 30)), key = ElementKey("area"))
                        }
                    },
                    root,
                    IntSize(200, 100),
                )
            try {
                val input = root.querySelector("input") as HTMLInputElement
                val frame = input.style.getPropertyValue("border-image-source")
                assertEquals(1, probe.encodings)
                assertContentEquals(image.copyArgb(), probe.lastPixels)
                input.focus()
                input.setSelectionRange(1, 3)
                repeat(3) {
                    revision.value += 1
                    host.render(IntSize(220 + it, 100))
                    assertSame(input, root.querySelector("input"))
                    assertEquals(1, input.selectionStart)
                    assertEquals(3, input.selectionEnd)
                    assertEquals(frame, input.style.getPropertyValue("border-image-source"))
                }
                assertEquals(1, probe.encodings)
            } finally {
                host.close()
                root.remove()
            }
        }
    }

    @Test
    fun onlyCurrentResolvedImageIdentitiesAreRetained() {
        val normal = createDrawImage(IntSize(3, 3), IntArray(9) { 0xff224466.toInt() })
        val focused = createDrawImage(IntSize(3, 3), IntArray(9) { 0xff668822.toInt() })
        val disabled = createDrawImage(IntSize(3, 3), IntArray(9) { 0xff886644.toInt() })
        val id = ResourceId("test", "editor")
        val appearance = TextInputAppearance.Custom(ImageSource.Resource(id), ImageSource.Pixels(focused), ArgbColor(-1), ImageSource.Pixels(disabled))
        val resources = WebUiResources(images = mapOf(id to normal))
        val element = document.createElement("input") as HTMLElement
        val editor = WebEditorPresentation(1, false, "retained", 100, true, false, TextStyle.TextField, appearance, "12px monospace", false, 0, 0.0)
        CanvasEncodingProbe().use { probe ->
            WebEditorDom().use { dom ->
                val frames = js("Object.values")(dom).unsafeCast<Array<Any>>().single().asDynamic()

                fun render(current: WebEditorPresentation) {
                    dom.beginFrame()
                    dom.update(element, current, resources)
                    dom.endFrame()
                }
                render(editor)
                assertEquals(1, probe.encodings)
                assertTrue(frames.has(normal) as Boolean)
                render(editor.copy(focused = true))
                assertEquals(2, probe.encodings)
                assertContentEquals(focused.copyArgb(), probe.lastPixels)
                assertTrue(frames.has(focused) as Boolean)
                assertFalse(frames.has(normal) as Boolean)
                render(editor.copy(focused = true, enabled = false))
                assertEquals(3, probe.encodings)
                assertContentEquals(disabled.copyArgb(), probe.lastPixels)
                assertTrue(frames.has(disabled) as Boolean)
                assertFalse(frames.has(focused) as Boolean)
                val equalPixels = createDrawImage(normal.size, normal.copyArgb())
                assertEquals(normal, equalPixels)
                assertNotSame(normal, equalPixels)
                val equalAppearance = appearance.copy(normal = ImageSource.Pixels(equalPixels))
                render(editor.copy(appearance = equalAppearance))
                render(editor)
                assertEquals(5, probe.encodings)
                repeat(32) { index ->
                    val replacement = createDrawImage(normal.size, IntArray(9) { 0xff000000.toInt() or index })
                    render(editor.copy(appearance = appearance.copy(normal = ImageSource.Pixels(replacement))))
                    assertEquals(1, frames.size as Int)
                    assertTrue(frames.has(replacement) as Boolean)
                    assertFalse(frames.has(normal) as Boolean)
                }
                render(editor.copy(appearance = TextInputAppearance.Default))
                assertEquals(0, frames.size as Int)
                assertEquals("", element.style.getPropertyValue("border-image-source"))
                val second = document.createElement("textarea") as HTMLElement
                dom.beginFrame()
                dom.update(element, editor, resources)
                dom.update(second, editor.copy(identity = 2, multiline = true, focused = true), resources)
                dom.endFrame()
                assertEquals(2, frames.size as Int)
                assertTrue(frames.has(normal) as Boolean)
                assertTrue(frames.has(focused) as Boolean)
                dom.beginFrame()
                dom.endFrame()
                assertEquals(0, frames.size as Int)
                render(editor)
                dom.close()
                assertEquals(0, frames.size as Int)
            }
        }
    }

    @Test
    fun hostsReleaseIndependentFramesOnCloseAndFailedEncoding() {
        val image = createDrawImage(IntSize(3, 3), IntArray(9) { 0xff224466.toInt() })
        val source = ImageSource.Pixels(image)
        val appearance = mutableStateOf(TextInputAppearance.Custom(source, source, ArgbColor(-1)))
        val firstRoot = document.createElement("div") as HTMLElement
        val secondRoot = document.createElement("div") as HTMLElement
        CanvasEncodingProbe().use { probe ->
            val firstState = TextFieldState("first")
            val secondState = TextFieldState("second")
            val first = mountWeb(UiDefinition { TextField(firstState, appearance.value, IntSize(160, 30)) }, firstRoot, IntSize(200, 40))
            val second = mountWeb(UiDefinition { TextField(secondState, appearance.value, IntSize(160, 30)) }, secondRoot, IntSize(200, 40))
            try {
                val firstFrames = frames(first)
                val secondFrames = frames(second)
                assertNotSame(firstFrames, secondFrames)
                assertEquals(2, probe.encodings)
                first.close()
                assertEquals(0, firstFrames.size as Int)
                assertEquals(1, secondFrames.size as Int)
                val replacement = createDrawImage(image.size, IntArray(9) { 0xff668822.toInt() })
                appearance.value = appearance.value.copy(normal = ImageSource.Pixels(replacement))
                probe.failurePoint = FailurePoint.Encode
                assertSame(probe.failure, assertFailsWith<IllegalStateException> { second.render(IntSize(200, 40)) })
                assertEquals(0, secondFrames.size as Int)
                assertEquals(0, secondRoot.childNodes.length)
                assertTrue(probe.canvases.all { it.width == 0 && it.height == 0 })
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun temporaryBitmapsAreReleasedAfterEachEncodingFailureStage() {
        val image = createDrawImage(IntSize(3, 3), IntArray(9) { 0xff224466.toInt() })
        for (stage in listOf(FailurePoint.Context, FailurePoint.Pixels, FailurePoint.Encode)) {
            CanvasEncodingProbe().use { probe ->
                probe.failurePoint = stage
                assertSame(probe.failure, assertFailsWith<IllegalStateException> { WebImageRasterizer.dataUrl(image) })
                assertEquals(0, probe.encodings)
                assertEquals(1, probe.canvases.size)
                assertEquals(0, probe.canvases.single().width)
                assertEquals(0, probe.canvases.single().height)
            }
        }
    }

    private fun frames(host: WebUiHost): dynamic {
        val renderer = js("Object.values")(host).unsafeCast<Array<Any?>>().filterIsInstance<WebDomRenderer>().single()
        val editors = js("Object.values")(renderer).unsafeCast<Array<Any?>>().filterIsInstance<WebEditorDom>().single()
        return js("Object.values")(editors).unsafeCast<Array<Any>>().single()
    }

    /**
     * Browser-owned allocation or export boundary selected for deterministic failure controls.
     */
    private enum class FailurePoint { None, Context, Pixels, Encode }

    /**
     * Counts actual browser PNG exports without adding a production measurement hook.
     */
    private class CanvasEncodingProbe : AutoCloseable {
        private val originalCreate = document.asDynamic().createElement
        private val originalDescriptor = js("Object.getOwnPropertyDescriptor")(document, "createElement")
        private val hadOwnCreator = js("Object.prototype.hasOwnProperty").call(document, "createElement") as Boolean

        /**
         * Number of completed calls to the browser's existing encoder.
         */
        var encodings: Int = 0
            private set

        /**
         * Detached pixels submitted to the last successful browser PNG export.
         */
        var lastPixels: IntArray = IntArray(0)
            private set

        /**
         * Temporary bitmaps retained by the test to verify immediate resource release.
         */
        val canvases = mutableListOf<HTMLCanvasElement>()

        /**
         * Failure identity and boundary configured by the current control.
         */
        val failure = IllegalStateException("browser encoding failure")
        var failurePoint = FailurePoint.None

        init {
            document.asDynamic().createElement = { name: String ->
                val element = originalCreate.call(document, name) as HTMLElement
                if (element is HTMLCanvasElement) {
                    canvases += element
                    if (failurePoint == FailurePoint.Context) {
                        element.asDynamic().getContext = { _: String -> throw failure }
                    } else if (failurePoint == FailurePoint.Pixels) {
                        val context = checkNotNull(element.getContext("2d") as? CanvasRenderingContext2D)
                        context.asDynamic().createImageData = { _: Double, _: Double -> throw failure }
                    }
                    val originalEncode = element.asDynamic().toDataURL
                    element.asDynamic().toDataURL = {
                        if (failurePoint == FailurePoint.Encode) throw failure
                        val result = originalEncode.call(element)
                        val context = checkNotNull(element.getContext("2d") as? CanvasRenderingContext2D)
                        val pixels = context.getImageData(0.0, 0.0, element.width.toDouble(), element.height.toDouble()).data
                        lastPixels =
                            IntArray(element.width * element.height) { index ->
                                val offset = index * 4
                                val red = pixels.asDynamic()[offset] as Int
                                val green = pixels.asDynamic()[offset + 1] as Int
                                val blue = pixels.asDynamic()[offset + 2] as Int
                                val alpha = pixels.asDynamic()[offset + 3] as Int
                                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                        encodings += 1
                        result
                    }
                }
                element
            }
        }

        override fun close() {
            if (hadOwnCreator) {
                js("Object.defineProperty")(document, "createElement", originalDescriptor)
            } else {
                js("Reflect.deleteProperty")(document, "createElement")
            }
        }
    }
}
