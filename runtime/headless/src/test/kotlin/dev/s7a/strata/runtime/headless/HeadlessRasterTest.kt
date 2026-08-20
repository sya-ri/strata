package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies low-level headless raster semantics independent of the retained tree.
 */
internal class HeadlessRasterTest {
    @Test
    fun emptyCommandsProduceTransparentBlackPixels() {
        val image = rasterizeHeadless(emptyList(), IntSize(2, 2))

        assertEquals(IntSize(2, 2), image.size)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), image.copyArgb())
        assertEquals(0, image.argbAt(0, 0))
    }

    @Test
    fun commandsAreOrderedAndClippedAtEveryViewportEdge() {
        val commands =
            listOf(
                fill(
                    IntRect(-2, -2, 2, 2),
                    0xFF112233.toInt(),
                ),
                fill(
                    IntRect(1, 1, 5, 5),
                    0xFF445566.toInt(),
                ),
                fill(
                    IntRect(2, 2, 2, 2),
                    0xFFFFFFFF.toInt(),
                ),
                fill(
                    IntRect(-10, 2, 1, 4),
                    0xFF778899.toInt(),
                ),
            )

        val image = rasterizeHeadless(commands, IntSize(3, 3))

        assertArrayEquals(
            intArrayOf(
                0xFF112233.toInt(),
                0xFF112233.toInt(),
                0,
                0xFF112233.toInt(),
                0xFF445566.toInt(),
                0xFF445566.toInt(),
                0xFF778899.toInt(),
                0xFF445566.toInt(),
                0xFF445566.toInt(),
            ),
            image.copyArgb(),
        )
    }

    @Test
    fun sourceOverUsesLongRoundedStraightAlphaAndCanonicalTransparency() {
        val image =
            rasterizeHeadless(
                listOf(
                    fill(
                        IntRect(0, 0, 1, 1),
                        0x800000FF.toInt(),
                    ),
                    fill(
                        IntRect(0, 0, 1, 1),
                        0x80FF0000.toInt(),
                    ),
                ),
                IntSize(1, 1),
            )
        val transparent =
            rasterizeHeadless(
                listOf(
                    fill(
                        IntRect(0, 0, 1, 1),
                        0x00000000,
                    ),
                ),
                IntSize(1, 1),
            )

        assertEquals(0xC0AA0055.toInt(), image.argbAt(0, 0))
        assertEquals(0, transparent.argbAt(0, 0))
    }

    @Test
    fun opaqueAndTransparentSourcesFollowReplacementAndNoOpRules() {
        val image =
            rasterizeHeadless(
                listOf(
                    fill(IntRect(0, 0, 1, 1), 0x80402010.toInt()),
                    fill(IntRect(0, 0, 1, 1), 0xFFFF0000.toInt()),
                    fill(IntRect(0, 0, 1, 1), 0x00112233),
                ),
                IntSize(1, 1),
            )
        val transparent =
            rasterizeHeadless(
                listOf(fill(IntRect(0, 0, 1, 1), 0x00112233)),
                IntSize(1, 1),
            )

        assertEquals(0xFFFF0000.toInt(), image.argbAt(0, 0))
        assertEquals(0, transparent.argbAt(0, 0))
    }

    @Test
    fun threeLayerVectorUsesDeclarationOrderAndIndependentRoundedResult() {
        val forward =
            rasterizeHeadless(
                listOf(
                    fill(IntRect(0, 0, 1, 1), 0x400000FF),
                    fill(IntRect(0, 0, 1, 1), 0x8000FF00.toInt()),
                    fill(IntRect(0, 0, 1, 1), 0xC0FF0000.toInt()),
                ),
                IntSize(1, 1),
            )
        val reverse =
            rasterizeHeadless(
                listOf(
                    fill(IntRect(0, 0, 1, 1), 0xC0FF0000.toInt()),
                    fill(IntRect(0, 0, 1, 1), 0x8000FF00.toInt()),
                    fill(IntRect(0, 0, 1, 1), 0x400000FF),
                ),
                IntSize(1, 1),
            )

        assertEquals(0xE8D32309.toInt(), forward.argbAt(0, 0))
        assertEquals(0xE84F6A46.toInt(), reverse.argbAt(0, 0))
    }

    @Test
    fun sourceOverRoundsExactHalfUpChannelTie() {
        val image =
            rasterizeHeadless(
                listOf(
                    fill(IntRect(0, 0, 1, 1), 0x02FE0000),
                    fill(IntRect(0, 0, 1, 1), 0x02000000),
                ),
                IntSize(1, 1),
            )

        assertEquals(0x047F0000, image.argbAt(0, 0))
    }

    @Test
    fun fullyOffscreenNonemptyRectIsAVisibleNoOp() {
        val image =
            rasterizeHeadless(
                listOf(fill(IntRect(-4, -3, -1, -1), 0xFFFF00FF.toInt())),
                IntSize(2, 2),
            )

        assertArrayEquals(intArrayOf(0, 0, 0, 0), image.copyArgb())
    }

    @Test
    fun nonemptyRectBelowViewportTakesBottomClippingBranch() {
        val image =
            rasterizeHeadless(
                listOf(fill(IntRect(0, 3, 1, 4), 0xFFFF00FF.toInt())),
                IntSize(2, 2),
            )

        assertArrayEquals(intArrayOf(0, 0, 0, 0), image.copyArgb())
    }

    @Test
    fun scaleReplicatesEveryLogicalPixelExactly() {
        val image =
            rasterizeHeadless(
                listOf(
                    fill(
                        IntRect(0, 0, 1, 1),
                        0xFFFF0000.toInt(),
                    ),
                    fill(
                        IntRect(1, 0, 2, 1),
                        0xFF00FF00.toInt(),
                    ),
                ),
                IntSize(2, 1),
                scale = 2,
            )

        assertEquals(IntSize(4, 2), image.size)
        assertArrayEquals(
            intArrayOf(
                0xFFFF0000.toInt(),
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF00FF00.toInt(),
                0xFFFF0000.toInt(),
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF00FF00.toInt(),
            ),
            image.copyArgb(),
        )
    }

    @Test
    fun commandInputAndOutputArraysAreDetached() {
        val commands = ArrayList<DrawCommand>()
        commands += fill(IntRect(0, 0, 1, 1), 0xFF123456.toInt())
        val image = rasterizeHeadless(commands, IntSize(1, 1))
        commands[0] = fill(IntRect(0, 0, 1, 1), 0xFFABCDEF.toInt())
        val firstCopy = image.copyArgb()
        firstCopy[0] = 0
        val secondCopy = image.copyArgb()

        assertEquals(0xFF123456.toInt(), image.argbAt(0, 0))
        assertNotSame(firstCopy, secondCopy)
        assertEquals(0xFF123456.toInt(), secondCopy[0])
    }

    @Test
    fun dimensionsAndCoordinatesAreValidatedBeforeRasterAllocation() {
        assertThrows<IllegalArgumentException> {
            rasterizeHeadless(emptyList(), IntSize(0, 1))
        }
        assertThrows<IllegalArgumentException> {
            rasterizeHeadless(emptyList(), IntSize(1, 0))
        }
        assertThrows<IllegalArgumentException> {
            rasterizeHeadless(emptyList(), IntSize(1, 1), scale = 0)
        }
        assertThrows<IllegalArgumentException> {
            rasterizeHeadless(emptyList(), IntSize(1, 1), scale = -1)
        }
        assertThrows<ArithmeticException> {
            rasterizeHeadless(emptyList(), IntSize(Int.MAX_VALUE, 1), scale = 2)
        }
        assertThrows<ArithmeticException> {
            rasterizeHeadless(emptyList(), IntSize(1, Int.MAX_VALUE), scale = 2)
        }
        assertThrows<ArithmeticException> {
            rasterizeHeadless(emptyList(), IntSize(50_000, 50_000))
        }
        val image = rasterizeHeadless(emptyList(), IntSize(1, 1))
        assertThrows<IllegalArgumentException> { image.argbAt(-1, 0) }.also { failure ->
            assertEquals("X coordinate must be inside the image.", failure.message)
        }
        assertThrows<IllegalArgumentException> { image.argbAt(1, 0) }.also { failure ->
            assertEquals("X coordinate must be inside the image.", failure.message)
        }
        assertThrows<IllegalArgumentException> { image.argbAt(0, -1) }.also { failure ->
            assertEquals("Y coordinate must be inside the image.", failure.message)
        }
        assertThrows<IllegalArgumentException> { image.argbAt(0, 1) }.also { failure ->
            assertEquals("Y coordinate must be inside the image.", failure.message)
        }
    }

    private fun fill(
        bounds: IntRect,
        color: Int,
    ): DrawCommand.FillRectangle = DrawCommand.FillRectangle(bounds, ArgbColor(color))
}
