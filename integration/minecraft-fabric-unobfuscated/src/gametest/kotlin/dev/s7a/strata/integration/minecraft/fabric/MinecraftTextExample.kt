package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:text
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap

/**
 * Builds a complete resource-font Text showcase with explicit line breaks and bounded wrapping.
 *
 * The active profile must provide resource fonts for the illustrated Japanese, Korean, and emoji glyphs.
 * @return one-shot definition whose complete frame shows at most four lines without changing the original semantic label.
 */
internal fun createTextShowcaseScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Text showcase") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(192, 88)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .padding(8),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "日本語 / 한글 / 🙂\n" +
                    "This paragraph wraps to the available width.\n" +
                    "Explicit newlines stay separate.\n" +
                    "Only four lines are visible; extra text receives an ellipsis.",
                layout = TextLayout.Multiline(wrap = TextWrap.Word, maxLines = 4, overflow = TextOverflow.Ellipsis, lineSpacing = 2),
            )
        }
    }
// showcase-source-end:text
