package dev.s7a.strata.integration.docs
// showcase-source-begin:row
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the Row element description used for topology validation and rendering.
 *
 * @return the public Row element tree.
 */
internal fun rowDescription(): Element =
    buildUi {
        Row(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 2,
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(12, 12)
                        .background(ArgbColor(0xFF22D3EE.toInt())),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(14, 16)
                        .background(ArgbColor(0xFFA78BFA.toInt()))
                        .weight(1f, fill = false),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(12, 8)
                        .background(ArgbColor(0xFFFBBF24.toInt()))
                        .align(VerticalAlignment.Bottom),
            )
        }
    }

/**
 * Renders the Row showcase scene into a deterministic headless frame.
 *
 * @return the rendered Row frame.
 */
internal fun row(): HeadlessFrame = renderHeadless(rowDescription(), IntSize(72, 28), scale = 3)
// showcase-source-end:row
