@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition

/**
 * API-only local screen example combining centered Stack composition with an application-owned sizing modifier.
 */
public object ApiOnlyResponsivePanel {
    /**
     * Creates a screen whose panel uses half of each viewport axis when both logical dimensions reach [minimumViewport].
     * Smaller viewports use both axes in full; resizing remeasures the retained panel without rebuilding this definition.
     * The solid panel closes on activation and has no fixed-size controls that would reject very small constraints.
     */
    public fun createDefinition(minimumViewport: IntSize = IntSize(480, 270)): UiDefinition =
        UiDefinition("Responsive panel") {
            ResponsivePanel(minimumViewport) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .fillMaxSize()
                            .background(ArgbColor(0xFF303030.toInt()))
                            .onActivate { close() }
                            .semantics(Semantics(label = UiText.Literal("Close responsive panel"), role = SemanticsRole.Button)),
                )
            }
        }

    /**
     * Centers one clipped panel within the parent's bounded available logical size.
     * Half sizes round down; the full-size branch is selected when either dimension is below its positive threshold.
     * [panelModifier] runs inside sizing and clipping, and [content] supplies ordinary application components.
     * Keep this composition at the screen root to make its available size the current logical viewport.
     */
    @Suppress("FunctionName")
    public fun UiScope.ResponsivePanel(
        minimumViewport: IntSize = IntSize(480, 270),
        panelModifier: Modifier = Modifier.Empty,
        content: UiScope.() -> Unit,
    ) {
        Stack(modifier = Modifier.Empty.fillMaxSize(), contentAlignment = Alignment.Center) {
            Stack(
                modifier = Modifier.Empty.then(Description(minimumViewport)).then(panelModifier),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }

    private data class Description(
        val minimumViewport: IntSize,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = TYPE
    }

    private class ResponsiveNode(
        var minimumViewport: IntSize,
    ) : ModifierNode(),
        ClipChildrenNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.maxWidth < Int.MAX_VALUE && constraints.maxHeight < Int.MAX_VALUE) {
                "ResponsivePanel requires bounded available dimensions."
            }
            val roomy = minimumViewport.width <= constraints.maxWidth && minimumViewport.height <= constraints.maxHeight
            val divisor = if (roomy) 2 else 1
            val size = constraints.constrain(IntSize(constraints.maxWidth / divisor, constraints.maxHeight / divisor))
            return scope.measureChild(0, Constraints.fixed(size.width, size.height))
        }
    }

    private val TYPE: ModifierNodeType<Description, ResponsiveNode> =
        ModifierNodeType(
            elementClass = Description::class,
            nodeClass = ResponsiveNode::class,
            validateLocal = { description ->
                require(0 < description.minimumViewport.width && 0 < description.minimumViewport.height) {
                    "ResponsivePanel thresholds must be positive logical dimensions."
                }
            },
            createNode = { description -> ResponsiveNode(description.minimumViewport) },
            updateNode = { previous, current, node ->
                node.minimumViewport = current.minimumViewport
                if (previous == current) DirtyMask.None else DirtyMask.of(DirtyPhase.Measure)
            },
        )
}
