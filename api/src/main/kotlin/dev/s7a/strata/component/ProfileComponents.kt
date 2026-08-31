@file:JvmName("ProfileComponents")
@file:Suppress("FunctionNaming", "TooManyFunctions", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Emits one determinate Minecraft-profile progress bar.
 *
 * The value is clamped to the closed zero-to-one range and the active resource pack supplies both the fill and border.
 *
 * @receiver active owner-thread screen scope.
 * @param progress completed fraction; non-finite values are rejected.
 * @param size positive logical destination size.
 * @param modifier active behavior applied to the bar.
 * @param key optional stable sibling identity.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.ProgressBar(
    progress: Double,
    size: IntSize = IntSize(100, 12),
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    require(progress.isFinite()) { "Progress must be finite." }
    element(ComponentRuntimeBridge.current().progressBar(progress.coerceIn(0.0, 1.0), size, modifier, key))
}

/**
 * Emits the active Minecraft profile's discrete loading animation.
 *
 * The animation observes explicit host frame time and repaints only when its native animation cell changes.
 *
 * @receiver active owner-thread screen scope.
 * @param size positive logical destination size.
 * @param modifier active behavior applied to the indicator.
 * @param key optional stable sibling identity.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.LoadingIndicator(
    size: IntSize = IntSize(10, 4),
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().loadingIndicator(size, modifier, key))
}

/**
 * Emits one Minecraft-profile Checkbox backed by caller-owned [state].
 *
 * Native pointer and focused keyboard input update the state before emitting [dev.s7a.strata.action.ComponentActions.CheckedChange] through the Modifier chain.
 *
 * @receiver active owner-thread screen scope.
 * @param label unresolved visible and semantic label.
 * @param state caller-owned selected state.
 * @param width maximum fixed logical width including the 20-pixel box and four-pixel gap.
 * @param enabled whether input and enabled semantics are active.
 * @param modifier active layout, input, and typed action behavior.
 * @param key optional stable sibling identity.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Checkbox(
    label: UiText,
    state: CheckboxState,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().checkbox(label, state, width, enabled, modifier, key))
}

/**
 * Emits one finite-option Minecraft-profile CycleButton.
 *
 * Pointer press, wheel, and focused keyboard input update caller-owned [state] before emitting the typed cycle action through [modifier].
 * Labels are evaluated once for the immutable option snapshot and must be supported by the active profile.
 * The default label uses the display conversion owned by [state]; an explicit [label] may instead provide translated or composed text.
 * Label or state-conversion exceptions propagate synchronously before element emission.
 *
 * @param T immutable option type.
 * @receiver active owner-thread screen scope.
 * @param state caller-owned finite option state.
 * @param width fixed logical button width.
 * @param enabled whether input and enabled appearance are active.
 * @param modifier active layout, input, and typed action behavior.
 * @param key optional stable sibling identity.
 * @param label maps each option to its visible unresolved label, defaulting to the state-owned display conversion.
 * @throws IllegalStateException when the scope is unavailable, no profile runtime is active, or the default conversion is evaluated outside the state-owning thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun <T : Any> UiScope.CycleButton(
    state: CycleButtonState<T>,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    label: (T) -> UiText = { value -> UiText.Literal(state.formatKnownMember(value)) },
) {
    checkUsable()
    val labels = state.values.map(label)
    element(ComponentRuntimeBridge.current().cycleButton(state, labels, width, enabled, modifier, key))
}

/**
 * Emits one horizontal Minecraft-profile Slider backed by caller-owned [state].
 *
 * Pointer press and drag plus focused left/right keys normalize through the state's range and steps before emitting a typed Slider change action.
 *
 * @receiver active owner-thread screen scope.
 * @param label unresolved visible and semantic label.
 * @param state caller-owned numeric state.
 * @param width fixed logical track width.
 * @param enabled whether input and enabled appearance are active.
 * @param modifier active layout, input, and typed action behavior.
 * @param key optional stable sibling identity.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Slider(
    label: UiText,
    state: SliderState,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().slider(label, state, width, enabled, modifier, key))
}

/**
 * Literal-label overload of [Slider].
 */
public fun UiScope.Slider(
    label: String,
    state: SliderState,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Slider(UiText.Literal(label), state, width, enabled, modifier, key)
}

/**
 * Literal-label overload of [Checkbox].
 */
public fun UiScope.Checkbox(
    label: String,
    state: CheckboxState,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Checkbox(UiText.Literal(label), state, width, enabled, modifier, key)
}

/**
 * Emits one immutable nearest-sampled image component.
 *
 * A resource source is resolved from the active runtime resource manager while a pixel source retains its immutable snapshot directly.
 * The complete source maps to [size], or to its natural pixel size when [size] is null.
 *
 * @receiver active owner-thread screen scope.
 * @param source platform-neutral pixel or resource source.
 * @param size optional exact logical destination size.
 * @param modifier active behavior applied to the image.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the resolved image or requested destination is invalid.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Image(
    source: ImageSource,
    size: IntSize? = null,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().image(source, null, size, modifier, key))
}

/**
 * Emits one immutable nearest-sampled source region from an image.
 *
 * @receiver active owner-thread screen scope.
 * @param source platform-neutral pixel or resource source.
 * @param sourceRegion nonempty half-open source rectangle contained by the resolved image.
 * @param size exact positive logical destination size, defaulting to the source-region size.
 * @param modifier active behavior applied to the image.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the source region or destination is invalid.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Image(
    source: ImageSource,
    sourceRegion: IntRect,
    size: IntSize = IntSize(sourceRegion.width, sourceRegion.height),
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().image(source, sourceRegion, size, modifier, key))
}

/**
 * Emits one square layered player head at a pixel-perfect integer scale.
 *
 * Pixel sources render immediately.
 * Lookup sources are resolved asynchronously by the active runtime, publish only at a frame boundary, and ignore completions from superseded sources.
 * [loadingContent] and [failureContent] each emit zero or one root and are displayed only for their matching state.
 *
 * @receiver active owner-thread screen scope.
 * @param source immutable pixels or a platform-neutral profile lookup.
 * @param scale positive integer scale mapping every source texel to an equal square of logical pixels.
 * @param showHat whether the outer hat layer is painted after the face.
 * @param modifier active behavior applied to the head.
 * @param key optional stable sibling identity.
 * @param loadingContent optional zero-or-one-root loading presentation.
 * @param failureContent optional zero-or-one-root failure presentation.
 * @throws IllegalArgumentException when pixels or state callback cardinality is invalid.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
public fun UiScope.PlayerHead(
    source: PlayerSkinSource = PlayerSkinSource.CurrentPlayer,
    scale: PlayerHeadScale,
    showHat: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    loadingContent: (UiScope.() -> Unit)? = null,
    failureContent: (UiScope.() -> Unit)? = null,
) {
    emitPlayerHead(source, scale.logicalSize, showHat, modifier, key, loadingContent, failureContent)
}

/**
 * Emits one square layered player head at an arbitrary logical size.
 *
 * Sizes divisible by eight retain nearest-neighbor texel scaling.
 * Other sizes use bilinear interpolation within each face or hat region so atlas pixels outside that region cannot bleed into the result.
 * Pixel sources render immediately, while lookup sources and fallback content follow the same lifecycle as the typed-scale overload.
 *
 * @receiver active owner-thread screen scope.
 * @param source immutable pixels or a platform-neutral profile lookup.
 * @param size positive logical square extent.
 * @param showHat whether the outer hat layer is painted after the face.
 * @param modifier active behavior applied to the head.
 * @param key optional stable sibling identity.
 * @param loadingContent optional zero-or-one-root loading presentation.
 * @param failureContent optional zero-or-one-root failure presentation.
 * @throws IllegalArgumentException when pixels, [size], state callback cardinality, or a non-divisible size above 1,024 is invalid.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@Deprecated(
    message = "Use the PlayerHeadScale overload for pixel-perfect integer scaling. Arbitrary sizes remain supported here and use bilinear interpolation when size is not divisible by eight.",
)
public fun UiScope.PlayerHead(
    source: PlayerSkinSource = PlayerSkinSource.CurrentPlayer,
    size: Int = 24,
    showHat: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    loadingContent: (UiScope.() -> Unit)? = null,
    failureContent: (UiScope.() -> Unit)? = null,
) {
    emitPlayerHead(source, size, showHat, modifier, key, loadingContent, failureContent)
}

/**
 * Emits the shared retained implementation after either public sizing contract has selected its logical extent.
 */
@OptIn(InternalStrataRuntimeApi::class)
private fun UiScope.emitPlayerHead(
    source: PlayerSkinSource,
    size: Int,
    showHat: Boolean,
    modifier: Modifier,
    key: ElementKey<*>?,
    loadingContent: (UiScope.() -> Unit)?,
    failureContent: (UiScope.() -> Unit)?,
) {
    checkUsable()
    val loading = loadingContent?.let(::buildOptionalComponentTree)
    val failure = failureContent?.let(::buildOptionalComponentTree)
    element(ComponentRuntimeBridge.current().playerHead(source, size, showHat, loading, failure, modifier, key))
}

/**
 * Emits one 18 by 18 inventory slot with an optional item root or synchronized binding.
 *
 * A bound slot observes authoritative menu storage before each frame and delegates pointer transactions through the runtime protocol.
 * An unbound slot may contain zero or one caller-defined 16 by 16 item root.
 *
 * @receiver active owner-thread screen scope.
 * @param bind optional immutable synchronized inventory locator.
 * @param highlightable whether pointer hover selects the profile highlight layers.
 * @param modifier active behavior applied before built-in inventory handling.
 * @param key optional stable sibling identity.
 * @param content optional zero-or-one-root unbound item presentation.
 * @throws IllegalArgumentException when binding and content conflict, callback cardinality is invalid, or a binding cannot be resolved.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Slot(
    bind: SlotBinding? = null,
    highlightable: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    content: (UiScope.() -> Unit)? = null,
) {
    checkUsable()
    require(bind == null || content == null) { "A bound Slot cannot also declare an item root." }
    val item = content?.let(::buildOptionalComponentTree)
    element(ComponentRuntimeBridge.current().slot(bind, highlightable, item, modifier, key))
}

/**
 * Emits one single-line profile-backed text component.
 *
 * @receiver active owner-thread screen scope.
 * @param text unresolved text retained for drawing and semantics.
 * @param style profile-backed color and shadow policy.
 * @param modifier active behavior applied to the text.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the profile cannot render [text].
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Text(
    text: UiText,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(text, TextLayout.SingleLine, style, modifier, key)
}

/**
 * Emits one literal single-line profile-backed text component.
 *
 * @receiver active owner-thread screen scope.
 * @param text literal converted to [UiText.Literal].
 * @param style profile-backed color and shadow policy.
 * @param modifier active behavior applied to the text.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the profile cannot render [text].
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
public fun UiScope.Text(
    text: String,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(UiText.Literal(text), style, modifier, key)
}

/**
 * Emits one single-line text component using an explicitly selected resource-pack font.
 *
 * The selected font is retained in [UiText.WithFont]; resource resolution belongs to the active runtime.
 * An inner [UiText.WithFont] keeps its own selection instead of inheriting [font].
 *
 * @receiver active owner-thread screen scope.
 * @param text unresolved text retained for drawing and semantics.
 * @param font structural identifier of the font definition.
 * @param style profile-backed color and shadow policy.
 * @param modifier active behavior applied to the text.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the profile cannot render [text].
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
public fun UiScope.Text(
    text: UiText,
    font: ResourceId,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(text.withFont(font), style, modifier, key)
}

/**
 * Emits one literal single-line text component using an explicitly selected resource-pack font.
 *
 * @receiver active owner-thread screen scope.
 * @param text literal converted to [UiText.Literal].
 * @param font structural identifier of the font definition.
 * @param style profile-backed color and shadow policy.
 * @param modifier active behavior applied to the text.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the profile cannot render [text].
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
public fun UiScope.Text(
    text: String,
    font: ResourceId,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(UiText.Literal(text), font, style, modifier, key)
}

/**
 * Emits one profile-backed text component with an explicit single-line or multiline layout.
 *
 * Multiline layout interprets supported line separators, wraps only at Unicode scalar boundaries, and retains the complete source for semantics.
 * It changes presentation without editing the supplied text or adding selection and clipboard behavior.
 *
 * @receiver active owner-thread screen scope.
 * @param text unresolved text retained for drawing and semantics.
 * @param layout explicit wrapping, line-count, overflow, and line-spacing policy.
 * @param style profile-backed color and shadow policy.
 * @param modifier active layout and behavior applied to the text.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the active profile cannot render [text] or the requested layout is incompatible with its constraints.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 * @throws UnsupportedOperationException when the active runtime does not support the requested layout.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Text(
    text: UiText,
    layout: TextLayout,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().text(text, layout, style, modifier, key))
}

/**
 * Literal overload of [Text] with the same explicit layout, ownership, and failure contract.
 */
public fun UiScope.Text(
    text: String,
    layout: TextLayout,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(UiText.Literal(text), layout, style, modifier, key)
}

/**
 * Emits explicitly laid-out text using a selected resource-pack font.
 *
 * The font is retained in [UiText.WithFont]; inner font selections retain precedence.
 * Resolution and any glyph resources belong to the active runtime, not this API description.
 *
 * @receiver active owner-thread screen scope.
 * @param text unresolved text retained for drawing and semantics.
 * @param layout explicit wrapping, line-count, overflow, and line-spacing policy.
 * @param font structural identifier of the font definition.
 * @param style profile-backed color and shadow policy.
 * @param modifier active layout and behavior applied to the text.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the active profile cannot render [text] or the requested layout is incompatible with its constraints.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 * @throws UnsupportedOperationException when the active runtime does not support the requested layout.
 */
public fun UiScope.Text(
    text: UiText,
    layout: TextLayout,
    font: ResourceId,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(text.withFont(font), layout, style, modifier, key)
}

/**
 * Literal overload of [Text] with the same explicit layout and font-selection contract.
 */
public fun UiScope.Text(
    text: String,
    layout: TextLayout,
    font: ResourceId,
    style: TextStyle = TextStyle.Normal,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Text(UiText.Literal(text), layout, font, style, modifier, key)
}

/**
 * Emits one 200 by 20 single-line text field.
 *
 * Editing preserves Unicode scalars while [TextFieldState.maxLength] counts UTF-16 code units.
 * Delivered preedit text remains inline presentation state until committed input arrives.
 * The component does not install a platform candidate window or add selection and clipboard commands.
 *
 * @receiver active owner-thread screen scope.
 * @param state owner-thread text value observed by the retained field.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed glyph style.
 * @param modifier active behavior applied to the field.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when the scope or state thread is invalid or no runtime evaluation is active.
 */
public fun UiScope.TextField(
    state: TextFieldState,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    TextField(state, IntSize(200, 20), enabled, textStyle, modifier, key)
}

/**
 * Emits one explicitly sized single-line text field.
 *
 * @receiver active owner-thread screen scope.
 * @param state owner-thread text value observed by the retained field.
 * @param size exact logical field extent.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed glyph style.
 * @param modifier active behavior applied to the field.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when [size] cannot contain the field or later constraints exclude it.
 * @throws IllegalStateException when the scope or state thread is invalid or no runtime evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextField(
    state: TextFieldState,
    size: IntSize,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().textField(state, size, enabled, textStyle, modifier, key))
}

/**
 * Emits one 200 by 20 single-line text field using an explicitly selected resource-pack font.
 *
 * @receiver active owner-thread screen scope.
 * @param state caller-owned text value observed by the retained field.
 * @param font structural identifier of the font definition.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed color and shadow policy.
 * @param modifier active behavior applied to the field.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when the scope or state thread is invalid or no runtime evaluation is active.
 */
public fun UiScope.TextField(
    state: TextFieldState,
    font: ResourceId,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    TextField(state, IntSize(200, 20), font, enabled, textStyle, modifier, key)
}

/**
 * Emits one explicitly sized single-line text field using a selected resource-pack font.
 *
 * The selected font supplies both drawing and cursor metrics from the runtime's pinned resource state.
 * Movement and deletion operate on Unicode scalars, not grapheme clusters, and [TextFieldState.maxLength] remains a UTF-16 bound.
 *
 * @receiver active owner-thread screen scope.
 * @param state caller-owned text value observed by the retained field.
 * @param size exact logical field extent.
 * @param font structural identifier of the font definition.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed color and shadow policy.
 * @param modifier active behavior applied to the field.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when [size] cannot contain the field or later constraints exclude it.
 * @throws IllegalStateException when the scope or state thread is invalid or no runtime evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextField(
    state: TextFieldState,
    size: IntSize,
    font: ResourceId,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.TextField,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().textField(state, size, enabled, textStyle, font, modifier, key))
}

/**
 * Emits one multiline editor with independently placeable vertical scrolling.
 *
 * Editing and deletion preserve Unicode scalars while [TextAreaState.maxLength] counts UTF-16 code units after newline normalization.
 * Soft wrapping does not insert line breaks into [state], and delivered IME preedit remains presentation until committed input arrives.
 * An external [Scrollbar] may share [TextAreaState.scrollState]; the editor does not add its own scrollbar.
 * This component does not add selection, clipboard commands, or grapheme-cluster editing.
 * Description creation does not claim [state]; the retained editor claims its sole text subscription on attachment.
 * The immutable description can be reused after detachment, but simultaneous attachment with the same state is rejected.
 *
 * @receiver active owner-thread screen scope.
 * @param state caller-owned text and scroll position observed by one retained editor.
 * @param viewport exact outer size or requested count of visible text rows.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed color and shadow policy.
 * @param wrap presentation-only wrapping policy.
 * @param lineSpacing non-negative additional logical pixels between adjacent lines.
 * @param modifier active layout, input, and typed action behavior.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when [lineSpacing] is negative or the requested viewport cannot contain the editor.
 * @throws IllegalStateException when the scope or state thread is invalid or no runtime evaluation is active; retained attachment also throws when [state] is already attached to another retained editor.
 * @throws UnsupportedOperationException when the active runtime does not support multiline editing.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextArea(
    state: TextAreaState,
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
    element(ComponentRuntimeBridge.current().textArea(state, viewport, enabled, textStyle, wrap, lineSpacing, modifier, key))
}

/**
 * Emits one multiline editor using an explicitly selected resource-pack font.
 *
 * The selected font supplies layout, drawing, cursor, and scroll metrics from the runtime's pinned resource state.
 * Text, IME, scrolling, scalar editing, and ownership follow the default-font [TextArea] contract.
 * Description creation does not claim [state]; the retained editor claims its sole text subscription on attachment.
 * The immutable description can be reused after detachment, but simultaneous attachment with the same state is rejected.
 *
 * @receiver active owner-thread screen scope.
 * @param state caller-owned text and scroll position observed by one retained editor.
 * @param viewport exact outer size or requested count of visible text rows.
 * @param font structural identifier of the font definition.
 * @param enabled whether focus and editing are accepted.
 * @param textStyle profile-backed color and shadow policy.
 * @param wrap presentation-only wrapping policy.
 * @param lineSpacing non-negative additional logical pixels between adjacent lines.
 * @param modifier active layout, input, and typed action behavior.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when [lineSpacing] is negative or the requested viewport cannot contain the editor.
 * @throws IllegalStateException when the scope or state thread is invalid or no runtime evaluation is active; retained attachment also throws when [state] is already attached to another retained editor.
 * @throws UnsupportedOperationException when the active runtime does not support multiline editing with explicit font selection.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.TextArea(
    state: TextAreaState,
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
    element(ComponentRuntimeBridge.current().textArea(state, viewport, enabled, textStyle, font, wrap, lineSpacing, modifier, key))
}

/**
 * Emits one fixed-height profile-backed button appearance and semantic surface.
 *
 * The component does not install focus or activation implicitly.
 * Compose [onActivate] with the same enabled state when primary pointer and focused Enter or Space presses represent one action, or use pointer modifiers for pointer-specific behavior.
 *
 * @receiver active owner-thread screen scope.
 * @param label unresolved button label.
 * @param width requested logical width.
 * @param enabled whether enabled appearance and semantics are used.
 * @param modifier active behavior including caller-owned focus, activation, and pointer actions.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the label does not fit or [width] is incompatible with the profile.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Button(
    label: UiText,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().button(label, width, enabled, modifier, key))
}

/**
 * Emits one literal fixed-height profile-backed button appearance and semantic surface.
 *
 * Like the typed-label overload, this component installs neither focus nor activation.
 *
 * @receiver active owner-thread screen scope.
 * @param label literal converted to [UiText.Literal].
 * @param width requested logical width.
 * @param enabled whether enabled appearance and semantics are used.
 * @param modifier active behavior including caller-owned focus, activation, and pointer actions.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when the label does not fit or [width] is incompatible with the profile.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
public fun UiScope.Button(
    label: String,
    width: Int = 150,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Button(UiText.Literal(label), width, enabled, modifier, key)
}

/**
 * Emits one externally controlled tab.
 *
 * The component renders [indicator] only while [selected] is true and emits matching tab semantics.
 * It installs neither focus nor activation; compose [onActivate] with the same enabled state for a shared primary-pointer and focused Enter-or-Space action, while pointer-specific actions remain ordinary modifier behavior.
 *
 * @receiver active owner-thread screen scope.
 * @param label unresolved tab label.
 * @param selected externally owned selected state.
 * @param width requested logical width.
 * @param enabled whether enabled appearance, hover, and semantics are used.
 * @param indicator selected-state presentation.
 * @param modifier active behavior including caller-owned focus, activation, and pointer actions.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when a custom indicator does not emit exactly one root.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Tab(
    label: UiText,
    selected: Boolean,
    width: Int = 150,
    enabled: Boolean = true,
    indicator: TabSelectionIndicator = TabSelectionIndicator.Underline,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    val customIndicator =
        if (selected && indicator is TabSelectionIndicator.Custom) {
            buildComponentTree(indicator.content)
        } else {
            null
        }
    element(ComponentRuntimeBridge.current().tab(label, selected, width, enabled, indicator, customIndicator, modifier, key))
}

/**
 * Literal overload of [Tab] with the same ownership, failure, and no-implicit-focus-or-activation contract.
 */
public fun UiScope.Tab(
    label: String,
    selected: Boolean,
    width: Int = 150,
    enabled: Boolean = true,
    indicator: TabSelectionIndicator = TabSelectionIndicator.Underline,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Tab(UiText.Literal(label), selected, width, enabled, indicator, modifier, key)
}

/**
 * Emits one profile-backed scroll viewport containing exactly one root.
 *
 * @receiver active owner-thread screen scope.
 * @param modifier active behavior applied to the viewport.
 * @param key optional stable sibling identity.
 * @param scrollRate positive logical wheel displacement multiplier.
 * @param content callback that must emit exactly one content root.
 * @throws IllegalArgumentException when [scrollRate] is not positive or [content] does not emit exactly one root.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.ScrollArea(
    state: ScrollState,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    scrollRate: Int = 9,
    content: UiScope.() -> Unit,
) {
    checkUsable()
    require(0 < scrollRate) { "Scroll rate must be positive." }
    val child = buildComponentTree(content)
    element(ComponentRuntimeBridge.current().scrollArea(state, child, scrollRate, modifier, key))
}

/**
 * Emits one independently placed profile-backed vertical scrollbar linked to [state].
 *
 * The scrollbar may be omitted or placed anywhere in the surrounding layout without changing its linked [ScrollArea].
 * Its height must be bounded by its parent or modifier.
 *
 * @receiver active owner-thread screen scope.
 * @param state caller-owned state shared with one scroll area.
 * @param modifier active layout and behavior around the scrollbar.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.Scrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    element(ComponentRuntimeBridge.current().scrollbar(state, modifier, key))
}

private fun buildOptionalComponentTree(content: UiScope.() -> Unit): Element? {
    val scope = UiScope.createRoot()
    return try {
        scope.content()
        when (scope.childElementsSnapshot().size) {
            0 -> null
            1 -> scope.rootElement()
            else -> throw IllegalArgumentException("An optional component callback may emit at most one root element.")
        }
    } finally {
        scope.close()
    }
}
