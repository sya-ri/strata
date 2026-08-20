package dev.s7a.strata.integration.docs
// showcase-source-begin:column
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the Column element description used for topology validation and rendering.
 *
 * @return the public Column element tree.
 */
internal fun columnDescription(): Element =
    buildUi {
        Column(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 2,
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = HorizontalAlignment.Center,
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
                        .align(HorizontalAlignment.End),
            )
        }
    }

/**
 * Renders the Column showcase scene into a deterministic headless frame.
 *
 * @return the rendered Column frame.
 */
internal fun column(): HeadlessFrame = renderHeadless(columnDescription(), IntSize(36, 68), scale = 3)
// showcase-source-end:column
