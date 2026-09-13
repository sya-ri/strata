@file:Suppress("FunctionNaming", "ktlint:standard:function-naming", "LongParameterList", "UNCHECKED_CAST")

package dev.s7a.strata.component

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import kotlin.jvm.JvmName

/**
 * Emits source-backed text using the same frame snapshot and retained-region ownership as [Observe].
 * The caller owns the source; text resolution and wrapping happen on the tree owner thread.
 * The complete modifier chain stays on the actual text node; the transparent region owns the sibling key and delegates parent data.
 */
@JvmName("TextUiTextSource")
public fun UiScope.Text(
    text: StateSource<UiText>,
    layout: TextLayout = TextLayout.SingleLine,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    emitObservedComponent(listOf(text), key) { values -> Text(values[0] as UiText, layout, style, modifier) }
}

/**
 * Literal source overload of [Text] with the same frame, layout, ownership, and failure contracts.
 */
@JvmName("TextStringSource")
public fun UiScope.Text(
    text: StateSource<String>,
    layout: TextLayout = TextLayout.SingleLine,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    emitObservedComponent(listOf(text), key) { values -> Text(values[0] as String, layout, style, modifier) }
}

/**
 * Source-backed text using an explicit font; nested font selections retain their existing precedence.
 */
@JvmName("TextUiTextSource")
public fun UiScope.Text(
    text: StateSource<UiText>,
    font: ResourceId,
    layout: TextLayout = TextLayout.SingleLine,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    emitObservedComponent(listOf(text), key) { values -> Text((values[0] as UiText).withFont(font), layout, style, modifier) }
}

/**
 * Literal source-backed text with an explicit font and the same retained observation contract.
 */
@JvmName("TextStringSource")
public fun UiScope.Text(
    text: StateSource<String>,
    font: ResourceId,
    layout: TextLayout = TextLayout.SingleLine,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    emitObservedComponent(listOf(text), key) { values -> Text(UiText.Literal(values[0] as String).withFont(font), layout, style, modifier) }
}
