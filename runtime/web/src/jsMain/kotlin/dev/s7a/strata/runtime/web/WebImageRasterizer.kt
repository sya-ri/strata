package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Samples immutable pixels at physical pixel centers, retaining no texture or historical image cache.
 * Each owned canvas contains only its current command; replacement and host close remove that canvas.
 */
internal object WebImageRasterizer {
    /**
     * Rasterizes one image operation without changing source geometry when clipped.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LoopWithTooManyJumpStatements") // Half-open coverage and discard branches follow the portable sampled-image contract.
    fun draw(
        canvas: HTMLCanvasElement,
        image: DrawImage,
        source: FloatRect,
        destination: FloatRect,
        bounds: IntRect,
        clip: FloatRect?,
        tint: ArgbColor,
        cutoff: Float,
        orientation: SampledImageOrientation,
        physicalSampling: Boolean = true,
    ) {
        val scale = window.devicePixelRatio.coerceAtLeast(1.0)
        val left = floor(bounds.left * scale).toInt()
        val top = floor(bounds.top * scale).toInt()
        val width = ceil(bounds.right * scale).toInt() - left
        val height = ceil(bounds.bottom * scale).toInt() - top
        require(width.toLong() * height <= 16_777_216L) { "Web image command exceeds its physical pixel budget." }
        canvas.width = width
        canvas.height = height
        canvas.style.left = "${left / scale}px"
        canvas.style.top = "${top / scale}px"
        canvas.style.width = "${width / scale}px"
        canvas.style.height = "${height / scale}px"
        val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
        val pixels = context.createImageData(width.toDouble(), height.toDouble())
        val reverseX = orientation == SampledImageOrientation.FlipHorizontal || orientation == SampledImageOrientation.FlipBoth
        val reverseY = orientation == SampledImageOrientation.FlipVertical || orientation == SampledImageOrientation.FlipBoth
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = (left + x + 0.5) / scale
                val py = (top + y + 0.5) / scale
                if (px < destination.left || destination.right <= px || py < destination.top || destination.bottom <= py) continue
                if (clip != null && (px < clip.left || clip.right <= px || py < clip.top || clip.bottom <= py)) continue
                val sampleX = if (physicalSampling) px else floor(px) + 0.5
                val sampleY = if (physicalSampling) py else floor(py) + 0.5
                val fx = (sampleX - destination.left) / destination.width
                val fy = (sampleY - destination.top) / destination.height
                val sx = floor(if (reverseX) source.right - fx * source.width else source.left + fx * source.width).toInt().coerceIn(floor(source.left).toInt(), ceil(source.right).toInt() - 1)
                val sy = floor(if (reverseY) source.bottom - fy * source.height else source.top + fy * source.height).toInt().coerceIn(floor(source.top).toInt(), ceil(source.bottom).toInt() - 1)
                val argb = image.argbAt(sx, sy)
                val alpha = (argb ushr 24) / 255.0 * (tint.value ushr 24) / 255.0
                if (alpha < cutoff) continue
                val offset = (y * width + x) * 4
                pixels.data.asDynamic()[offset] = (argb ushr 16 and 255) * (tint.value ushr 16 and 255) / 255.0
                pixels.data.asDynamic()[offset + 1] = (argb ushr 8 and 255) * (tint.value ushr 8 and 255) / 255.0
                pixels.data.asDynamic()[offset + 2] = (argb and 255) * (tint.value and 255) / 255.0
                pixels.data.asDynamic()[offset + 3] = alpha * 255.0
            }
        }
        context.putImageData(pixels, 0.0, 0.0)
    }

    /**
     * Encodes a small custom editor frame as an owned CSS image without external URLs.
     */
    fun dataUrl(image: DrawImage): String {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = image.size.width
        canvas.height = image.size.height
        val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
        val pixels = context.createImageData(image.size.width.toDouble(), image.size.height.toDouble())
        image.copyArgb().forEachIndexed { index, argb ->
            val offset = index * 4
            pixels.data.asDynamic()[offset] = argb ushr 16 and 255
            pixels.data.asDynamic()[offset + 1] = argb ushr 8 and 255
            pixels.data.asDynamic()[offset + 2] = argb and 255
            pixels.data.asDynamic()[offset + 3] = argb ushr 24
        }
        context.putImageData(pixels, 0.0, 0.0)
        return try {
            canvas.toDataURL()
        } finally {
            canvas.width = 0
            canvas.height = 0
        }
    }
}
