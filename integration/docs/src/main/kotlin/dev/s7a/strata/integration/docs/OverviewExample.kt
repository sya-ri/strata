package dev.s7a.strata.integration.docs
// showcase-source-begin:overview
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
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
 * Builds the overview tree shared by its metadata and renderer.
 *
 * @return the public element tree before rendering.
 */
internal fun overviewDescription(): Element =
    buildUi {
        Column(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 4,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Row(
                modifier = Modifier.Empty.size(60, 12),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = VerticalAlignment.Center,
            ) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 8)
                            .background(ArgbColor(0xFF22D3EE.toInt())),
                )
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 10)
                            .background(ArgbColor(0xFFA78BFA.toInt())),
                )
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 6)
                            .background(ArgbColor(0xFFFBBF24.toInt())),
                )
            }
            Box(
                modifier =
                    Modifier.Empty
                        .size(44, 16)
                        .background(ArgbColor(0xFF1F2937.toInt())),
                contentAlignment = Alignment.Center,
            ) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(24, 8)
                            .background(ArgbColor(0xFFFB7185.toInt()))
                            .align(Alignment.Center),
                )
            }
        }
    }

/**
 * Renders the overview scene into a deterministic headless frame.
 *
 * @return the rendered overview frame.
 */
internal fun overview(): HeadlessFrame = renderHeadless(overviewDescription(), IntSize(72, 44), scale = 3)
// showcase-source-end:overview
