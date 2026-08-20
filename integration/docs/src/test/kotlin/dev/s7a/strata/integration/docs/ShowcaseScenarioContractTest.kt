package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies typed catalog metadata and exact compiled headless showcase geometry.
 */
internal class ShowcaseScenarioContractTest {
    @Test
    fun catalogHasLockedOrderReferencesAndViewports() {
        ShowcaseScenarioCatalog.validate()
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            listOf(DocumentedComponent.Row, DocumentedComponent.Column, DocumentedComponent.Box, DocumentedComponent.Spacer),
            scenarios.map { scenario -> scenario.component },
        )
        assertEquals(
            listOf(
                "dev/s7a/strata/integration/docs/RowExample.kt",
                "dev/s7a/strata/integration/docs/ColumnExample.kt",
                "dev/s7a/strata/integration/docs/BoxExample.kt",
                "dev/s7a/strata/integration/docs/SpacerExample.kt",
            ),
            scenarios.map { scenario -> scenario.source.relativePath },
        )
        assertEquals(listOf("row", "column", "box", "spacer"), scenarios.map { scenario -> scenario.source.slug })
        assertEquals(listOf(IntSize(72, 28), IntSize(36, 68), IntSize(64, 36), IntSize(64, 28)), scenarios.map { scenario -> scenario.viewport })
        assertEquals(listOf(3, 3, 3, 3), scenarios.map { scenario -> scenario.scale })
    }

    @Test
    fun linearScenariosHaveExactTypedDetailsAndTopology() {
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            listOf(
                ShowcaseTreeDetail.FillMaxSize,
                ShowcaseTreeDetail.Background(canvas),
                ShowcaseTreeDetail.Padding(4),
                ShowcaseTreeDetail.Spacing(2),
                ShowcaseTreeDetail.Arrangement(Arrangement.SpaceEvenly),
                ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
            ),
            scenarios[0].tree.details,
        )
        assertEquals(
            listOf(
                ShowcaseTreeDetail.FillMaxSize,
                ShowcaseTreeDetail.Background(canvas),
                ShowcaseTreeDetail.Padding(4),
                ShowcaseTreeDetail.Spacing(2),
                ShowcaseTreeDetail.Arrangement(Arrangement.SpaceAround),
                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
            ),
            scenarios[1].tree.details,
        )
        assertEquals(listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer, DocumentedComponent.Spacer), scenarios[0].tree.children.map { child -> child.component })
        assertEquals(listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer, DocumentedComponent.Spacer), scenarios[1].tree.children.map { child -> child.component })
        assertEquals(
            listOf(
                listOf(ShowcaseTreeDetail.Size(12, 12), ShowcaseTreeDetail.Background(cyan)),
                listOf(ShowcaseTreeDetail.Size(14, 16), ShowcaseTreeDetail.Background(violet), ShowcaseTreeDetail.Weight(1f, false)),
                listOf(ShowcaseTreeDetail.Size(12, 8), ShowcaseTreeDetail.Background(amber), ShowcaseTreeDetail.RowAlign(VerticalAlignment.Bottom)),
            ),
            scenarios[0].tree.children.map { child -> child.details },
        )
        assertEquals(
            listOf(
                listOf(ShowcaseTreeDetail.Size(12, 12), ShowcaseTreeDetail.Background(cyan)),
                listOf(ShowcaseTreeDetail.Size(14, 16), ShowcaseTreeDetail.Background(violet), ShowcaseTreeDetail.Weight(1f, false)),
                listOf(ShowcaseTreeDetail.Size(12, 8), ShowcaseTreeDetail.Background(amber), ShowcaseTreeDetail.ColumnAlign(HorizontalAlignment.End)),
            ),
            scenarios[1].tree.children.map { child -> child.details },
        )
    }

    @Test
    fun boxAndSpacerScenariosHaveExactTypedDetailsAndTopology() {
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            listOf(
                ShowcaseTreeDetail.FillMaxSize,
                ShowcaseTreeDetail.Background(canvas),
                ShowcaseTreeDetail.Padding(4),
                ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
            ),
            scenarios[2].tree.details,
        )
        assertEquals(
            listOf(
                ShowcaseTreeDetail.FillMaxSize,
                ShowcaseTreeDetail.Background(canvas),
                ShowcaseTreeDetail.Padding(4),
                ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
            ),
            scenarios[3].tree.details,
        )
        assertEquals(DocumentedComponent.Box, scenarios[3].tree.component)
        assertEquals(listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer, DocumentedComponent.Spacer), scenarios[2].tree.children.map { child -> child.component })
        assertEquals(listOf(DocumentedComponent.Spacer), scenarios[3].tree.children.map { child -> child.component })
        assertEquals(
            listOf(
                listOf(ShowcaseTreeDetail.Size(28, 16), ShowcaseTreeDetail.Background(cyan), ShowcaseTreeDetail.BoxAlign(Alignment.TopStart)),
                listOf(ShowcaseTreeDetail.Size(36, 20), ShowcaseTreeDetail.Background(violet)),
                listOf(ShowcaseTreeDetail.Size(20, 12), ShowcaseTreeDetail.Background(amber), ShowcaseTreeDetail.BoxAlign(Alignment.BottomEnd)),
            ),
            scenarios[2].tree.children.map { child -> child.details },
        )
        assertEquals(
            listOf(ShowcaseTreeDetail.Size(36, 12), ShowcaseTreeDetail.Background(rose), ShowcaseTreeDetail.BoxAlign(Alignment.Center)),
            scenarios[3]
                .tree.children
                .single()
                .details,
        )
    }

    @Test
    fun overviewHasExactRootAndContainerDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertEquals("dev/s7a/strata/integration/docs/OverviewExample.kt", overview.source.relativePath)
        assertEquals("overview", overview.source.slug)
        assertEquals(IntSize(72, 44), overview.viewport)
        assertEquals(3, overview.scale)
        assertEquals(DocumentedComponent.Column, overview.tree.component)
        assertEquals(
            listOf(
                ShowcaseTreeDetail.FillMaxSize,
                ShowcaseTreeDetail.Background(canvas),
                ShowcaseTreeDetail.Padding(4),
                ShowcaseTreeDetail.Spacing(4),
                ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
            ),
            overview.tree.details,
        )
        assertEquals(2, overview.tree.children.size)
        assertEquals(DocumentedComponent.Row, overview.tree.children[0].component)
        assertEquals(DocumentedComponent.Box, overview.tree.children[1].component)
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(60, 12),
                ShowcaseTreeDetail.Arrangement(Arrangement.SpaceEvenly),
                ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
            ),
            overview.tree.children[0].details,
        )
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(44, 16),
                ShowcaseTreeDetail.Background(panel),
                ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
            ),
            overview.tree.children[1].details,
        )
    }

    @Test
    fun overviewHasExactLeafTopologyAndDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertEquals(
            listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer, DocumentedComponent.Spacer),
            overview.tree.children[0]
                .children
                .map { child -> child.component },
        )
        assertEquals(
            listOf(DocumentedComponent.Spacer),
            overview.tree.children[1]
                .children
                .map { child -> child.component },
        )
        assertEquals(
            listOf(
                listOf(ShowcaseTreeDetail.Size(10, 8), ShowcaseTreeDetail.Background(cyan)),
                listOf(ShowcaseTreeDetail.Size(10, 10), ShowcaseTreeDetail.Background(violet)),
                listOf(ShowcaseTreeDetail.Size(10, 6), ShowcaseTreeDetail.Background(amber)),
            ),
            overview.tree.children[0]
                .children
                .map { child -> child.details },
        )
        assertEquals(
            listOf(ShowcaseTreeDetail.Size(24, 8), ShowcaseTreeDetail.Background(rose), ShowcaseTreeDetail.BoxAlign(Alignment.Center)),
            overview.tree.children[1]
                .children
                .single()
                .details,
        )
    }

    @Test
    fun componentScenariosHaveExactPhysicalFramesAndColorRectangles() {
        val expectations =
            listOf(
                FrameExpectation(
                    DocumentedComponent.Row,
                    IntSize(216, 84),
                    listOf(
                        ColorRectangle(canvas.value, 0, 0, 216, 84, 1552 * 9),
                        ColorRectangle(cyan.value, 27, 24, 63, 60, 144 * 9),
                        ColorRectangle(violet.value, 87, 18, 129, 66, 224 * 9),
                        ColorRectangle(amber.value, 150, 48, 186, 72, 96 * 9),
                    ),
                ),
                FrameExpectation(
                    DocumentedComponent.Column,
                    IntSize(108, 204),
                    listOf(
                        ColorRectangle(canvas.value, 0, 0, 108, 204, 1984 * 9),
                        ColorRectangle(cyan.value, 36, 21, 72, 57, 144 * 9),
                        ColorRectangle(violet.value, 33, 84, 75, 132, 224 * 9),
                        ColorRectangle(amber.value, 60, 156, 96, 180, 96 * 9),
                    ),
                ),
                FrameExpectation(
                    DocumentedComponent.Box,
                    IntSize(192, 108),
                    listOf(
                        ColorRectangle(canvas.value, 0, 0, 192, 108, 1192 * 9),
                        ColorRectangle(cyan.value, 12, 12, 96, 60, 232 * 9),
                        ColorRectangle(violet.value, 42, 24, 150, 84, 640 * 9),
                        ColorRectangle(amber.value, 120, 60, 180, 96, 240 * 9),
                    ),
                ),
                FrameExpectation(
                    DocumentedComponent.Spacer,
                    IntSize(192, 84),
                    listOf(
                        ColorRectangle(canvas.value, 0, 0, 192, 84, 1360 * 9),
                        ColorRectangle(rose.value, 42, 24, 150, 60, 432 * 9),
                    ),
                ),
            )
        val scenarios = ShowcaseScenarioCatalog.components.associateBy { scenario -> scenario.component }
        expectations.forEach { expectation ->
            val scenario = scenarios.getValue(expectation.component)
            val frame = scenario.render()
            assertEquals(scenario.viewport, frame.viewport)
            assertEquals(scenario.scale, frame.pixelScale)
            assertEquals(expectation.imageSize, frame.image.size)
            assertTrue(frame.semantics.isEmpty())
            assertTrue(frame.image.copyArgb().all { pixel -> pixel ushr 24 == 0xFF })
            expectation.rectangles.forEach { rectangle ->
                assertColorBounds(frame, rectangle)
            }
        }
    }

    @Test
    fun overviewHasExactPhysicalFrameAndColorRectangles() {
        val overview = ShowcaseScenarioCatalog.overview.render()
        assertEquals(IntSize(216, 132), overview.image.size)
        assertEquals(3, overview.pixelScale)
        assertTrue(overview.semantics.isEmpty())
        assertEquals(IntSize(72, 44), overview.viewport)
        assertTrue(overview.image.copyArgb().all { pixel -> pixel ushr 24 == 0xFF })
        listOf(
            ColorRectangle(canvas.value, 0, 0, 216, 132, 2224 * 9),
            ColorRectangle(cyan.value, 39, 24, 69, 48, 80 * 9),
            ColorRectangle(violet.value, 93, 21, 123, 51, 100 * 9),
            ColorRectangle(amber.value, 144, 27, 174, 45, 60 * 9),
            ColorRectangle(panel.value, 42, 66, 174, 114, 512 * 9),
            ColorRectangle(rose.value, 72, 78, 144, 102, 192 * 9),
        ).forEach { rectangle -> assertColorBounds(overview, rectangle) }
    }

    private fun assertColorBounds(
        frame: HeadlessFrame,
        expected: ColorRectangle,
    ) {
        var count = 0
        var left = frame.image.size.width
        var top = frame.image.size.height
        var right = 0
        var bottom = 0
        for (y in 0 until frame.image.size.height) {
            for (x in 0 until frame.image.size.width) {
                if (frame.image.argbAt(x, y) == expected.color) {
                    count += 1
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
        }
        assertEquals(expected.count, count)
        assertEquals(expected.left, left)
        assertEquals(expected.top, top)
        assertEquals(expected.right, right)
        assertEquals(expected.bottom, bottom)
    }

    private data class FrameExpectation(
        val component: DocumentedComponent,
        val imageSize: IntSize,
        val rectangles: List<ColorRectangle>,
    )

    private data class ColorRectangle(
        val color: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val count: Int,
    )

    private companion object {
        val canvas = ArgbColor(0xFF111827.toInt())
        val panel = ArgbColor(0xFF1F2937.toInt())
        val cyan = ArgbColor(0xFF22D3EE.toInt())
        val violet = ArgbColor(0xFFA78BFA.toInt())
        val amber = ArgbColor(0xFFFBBF24.toInt())
        val rose = ArgbColor(0xFFFB7185.toInt())
    }
}
