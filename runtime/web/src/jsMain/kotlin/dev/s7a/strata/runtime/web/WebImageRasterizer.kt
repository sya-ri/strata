package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.render.DrawCommand
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
     * Decodes each portable image command through the same viewport-bounded sampler.
     */
    fun draw(
        canvas: HTMLCanvasElement,
        command: DrawCommand,
        viewport: IntSize,
        clip: FloatRect?,
    ) {
        when (command) {
            is DrawCommand.SampledImage -> draw(canvas, command.image, command.source, command.destination, viewport, clip, command.tint, command.alphaCutoff, command.orientation)
            is DrawCommand.BlitImage -> draw(canvas, command.image, floating(command.source), floating(command.destination), viewport, clip, ArgbColor(-1), 0f, SampledImageOrientation.Normal, physicalSampling = false)
            is DrawCommand.BlitImagePixels -> draw(canvas, command.image, floating(command.source), floating(command.destination), viewport, clip, ArgbColor(-1), 0f, SampledImageOrientation.Normal)
            else -> error("Unexpected web image command.")
        }
    }

    private fun floating(bounds: IntRect): FloatRect = FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())

    /**
     * Rasterizes only covered pixel centers inside the viewport and clip, preserving the original source mapping.
     * Empty coverage releases the current bitmap without allocating image data; visible coverage retains the physical pixel budget.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LoopWithTooManyJumpStatements") // Half-open coverage and discard branches follow the portable sampled-image contract.
    fun draw(
        canvas: HTMLCanvasElement,
        image: DrawImage,
        source: FloatRect,
        destination: FloatRect,
        viewport: IntSize,
        clip: FloatRect?,
        tint: ArgbColor,
        cutoff: Float,
        orientation: SampledImageOrientation,
        physicalSampling: Boolean = true,
    ) {
        val scale = window.devicePixelRatio.coerceAtLeast(1.0)
        require(scale.isFinite()) { "Web image pixel density must be finite." }

        fun edge(
            value: Float,
            extent: Int,
        ): Double = ceil(value.toDouble().coerceIn(0.0, extent.toDouble()) * scale - 0.5)
        val left = edge(maxOf(destination.left, clip?.left ?: destination.left), viewport.width)
        val top = edge(maxOf(destination.top, clip?.top ?: destination.top), viewport.height)
        val columns = edge(minOf(destination.right, clip?.right ?: destination.right), viewport.width) - left
        val rows = edge(minOf(destination.bottom, clip?.bottom ?: destination.bottom), viewport.height) - top
        if (columns <= 0.0 || rows <= 0.0) {
            canvas.width = 0
            canvas.height = 0
            canvas.style.left = "0px"
            canvas.style.top = "0px"
            canvas.style.width = "0px"
            canvas.style.height = "0px"
            return
        }
        require(columns * rows <= 16_777_216.0) { "Web image command exceeds its physical pixel budget." }
        val width = columns.toInt()
        val height = rows.toInt()
        // Clear the old height before resizing so an intermediate bitmap cannot exceed the budget.
        canvas.height = 0
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
        return try {
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
            canvas.toDataURL()
        } finally {
            canvas.width = 0
            canvas.height = 0
        }
    }
}
