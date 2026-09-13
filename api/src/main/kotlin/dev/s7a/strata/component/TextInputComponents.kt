@file:Suppress("FunctionNaming", "ktlint:standard:function-naming", "LongParameterList", "TooManyFunctions")

package dev.s7a.strata.component

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap

/**
 * Emits a retained [TextField] with a per-editor [appearance].
 * Geometry, state ownership, font, wrapping, and input behavior follow the corresponding ordinary overload.
 * Appearance images are resolved at evaluation; invalid nine-slice borders fail before a frame is presented.
 * Keep state and images outside reevaluated content. Appearance-only changes preserve focus, composition, and scroll.
 * Use [TextStyle.ContainerLabel] for dark text on light frames; the appearance controls caret and composition colors.
 * @throws UnsupportedOperationException when the installed runtime lacks custom appearance support.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextField(
    state: TextFieldState,
    appearance: TextInputAppearance,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    TextField(state, appearance, IntSize(200, 20), enabled, textStyle, modifier, key)
}

/**
 * Emits a retained [TextField] with a per-editor [appearance].
 * Geometry, state ownership, font, wrapping, and input behavior follow the corresponding ordinary overload.
 * Appearance images are resolved at evaluation; invalid nine-slice borders fail before a frame is presented.
 * Keep state and images outside reevaluated content. Appearance-only changes preserve focus, composition, and scroll.
 * Use [TextStyle.ContainerLabel] for dark text on light frames; the appearance controls caret and composition colors.
 * @throws UnsupportedOperationException when the installed runtime lacks custom appearance support.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextField(
    state: TextFieldState,
    appearance: TextInputAppearance,
    size: IntSize,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().textField(state, size, appearance, enabled, textStyle, modifier, key))
}

/**
 * Emits a retained [TextField] with a per-editor [appearance].
 * Geometry, state ownership, font, wrapping, and input behavior follow the corresponding ordinary overload.
 * Appearance images are resolved at evaluation; invalid nine-slice borders fail before a frame is presented.
 * Keep state and images outside reevaluated content. Appearance-only changes preserve focus, composition, and scroll.
 * Use [TextStyle.ContainerLabel] for dark text on light frames; the appearance controls caret and composition colors.
 * @throws UnsupportedOperationException when the installed runtime lacks custom appearance support.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextField(
    state: TextFieldState,
    appearance: TextInputAppearance,
    font: ResourceId,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    TextField(state, appearance, IntSize(200, 20), font, enabled, textStyle, modifier, key)
}

/**
 * Emits a retained [TextField] with a per-editor [appearance].
 * Geometry, state ownership, font, wrapping, and input behavior follow the corresponding ordinary overload.
 * Appearance images are resolved at evaluation; invalid nine-slice borders fail before a frame is presented.
 * Keep state and images outside reevaluated content. Appearance-only changes preserve focus, composition, and scroll.
 * Use [TextStyle.ContainerLabel] for dark text on light frames; the appearance controls caret and composition colors.
 * @throws UnsupportedOperationException when the installed runtime lacks custom appearance support.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextField(
    state: TextFieldState,
    appearance: TextInputAppearance,
    size: IntSize,
    font: ResourceId,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().textField(state, size, appearance, enabled, textStyle, font, modifier, key))
}

/**
 * Emits a retained [TextArea] with a per-editor [appearance].
 * Geometry, state ownership, font, wrapping, and input behavior follow the corresponding ordinary overload.
 * Appearance images are resolved at evaluation; invalid nine-slice borders fail before a frame is presented.
 * Keep state and images outside reevaluated content. Appearance-only changes preserve focus, composition, and scroll.
 * Use [TextStyle.ContainerLabel] for dark text on light frames; the appearance controls caret and composition colors.
 * @throws UnsupportedOperationException when the installed runtime lacks custom appearance support.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextArea(
    state: TextAreaState,
    appearance: TextInputAppearance,
    viewport: TextAreaViewport,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    wrap: TextWrap = TextWrap.Word,
    lineSpacing: Int = 0,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    require(0 <= lineSpacing) { "Text area line spacing must be non-negative." }
    element(ComponentRuntimeBridge.current().textArea(state, viewport, appearance, enabled, textStyle, wrap, lineSpacing, modifier, key))
}

/**
 * Emits a retained [TextArea] with a per-editor [appearance].
 * Geometry, state ownership, font, wrapping, and input behavior follow the corresponding ordinary overload.
 * Appearance images are resolved at evaluation; invalid nine-slice borders fail before a frame is presented.
 * Keep state and images outside reevaluated content. Appearance-only changes preserve focus, composition, and scroll.
 * Use [TextStyle.ContainerLabel] for dark text on light frames; the appearance controls caret and composition colors.
 * @throws UnsupportedOperationException when the installed runtime lacks custom appearance support.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextArea(
    state: TextAreaState,
    appearance: TextInputAppearance,
    viewport: TextAreaViewport,
    font: ResourceId,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    wrap: TextWrap = TextWrap.Word,
    lineSpacing: Int = 0,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    require(0 <= lineSpacing) { "Text area line spacing must be non-negative." }
    element(ComponentRuntimeBridge.current().textArea(state, viewport, appearance, enabled, textStyle, font, wrap, lineSpacing, modifier, key))
}
