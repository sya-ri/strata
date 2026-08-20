package dev.s7a.strata.integration.docs
// showcase-source-begin:box
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the Box element description used for topology validation and rendering.
 *
 * @return the public Box element tree.
 */
internal fun boxDescription(): Element =
    buildUi {
        Box(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(28, 16)
                        .background(ArgbColor(0xFF22D3EE.toInt()))
                        .align(Alignment.TopStart),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(36, 20)
                        .background(ArgbColor(0xFFA78BFA.toInt())),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(20, 12)
                        .background(ArgbColor(0xFFFBBF24.toInt()))
                        .align(Alignment.BottomEnd),
            )
        }
    }

/**
 * Renders the Box showcase scene into a deterministic headless frame.
 *
 * @return the rendered Box frame.
 */
internal fun box(): HeadlessFrame = renderHeadless(boxDescription(), IntSize(64, 36), scale = 3)
// showcase-source-end:box
