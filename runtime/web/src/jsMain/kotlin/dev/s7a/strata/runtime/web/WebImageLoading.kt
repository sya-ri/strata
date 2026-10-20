package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Decodes a browser-accessible image to immutable straight ARGB pixels before UI evaluation.
 * Anonymous CORS, CSP and same-origin cookie rules apply; unreadable or oversized images fail.
 * The transient image and canvas are released on success, failure or coroutine cancellation.
 */
public suspend fun loadWebImage(
    url: String,
    maxPixels: Int = 16_777_216,
): DrawImage {
    require(url.isNotBlank() && 0 < maxPixels) { "Image URL and pixel budget must be valid." }
    return suspendCancellableCoroutine { continuation ->
        val image = document.createElement("img") as HTMLImageElement
        image.crossOrigin = "anonymous"

        fun release() {
            image.onload = null
            image.onerror = null
            image.removeAttribute("src")
        }
        image.onload = {
            val result =
                runCatching {
                    val width = image.naturalWidth
                    val height = image.naturalHeight
                    require(0 < width && 0 < height && width.toLong() * height <= maxPixels) { "Web image exceeds its pixel budget." }
                    val canvas = document.createElement("canvas") as HTMLCanvasElement
                    canvas.width = width
                    canvas.height = height
                    try {
                        val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
                        context.drawImage(image, 0.0, 0.0)
                        val rgba = context.getImageData(0.0, 0.0, width.toDouble(), height.toDouble()).data
                        createDrawImage(
                            IntSize(width, height),
                            IntArray(width * height) { index ->
                                val offset = index * 4
                                (rgba[offset + 3].toInt() shl 24) or (rgba[offset].toInt() shl 16) or (rgba[offset + 1].toInt() shl 8) or rgba[offset + 2].toInt()
                            },
                        )
                    } finally {
                        canvas.width = 0
                        canvas.height = 0
                    }
                }
            release()
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
        }
        image.onerror = { _, _, _, _, _ ->
            release()
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Web image could not be decoded under browser resource policy."))
        }
        continuation.invokeOnCancellation { release() }
        if (continuation.isActive) image.src = url
    }
}
