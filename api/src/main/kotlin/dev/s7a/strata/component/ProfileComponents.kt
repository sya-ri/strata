@file:JvmName("ProfileComponents")
@file:Suppress("FunctionNaming", "TooManyFunctions", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

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
 * Emits one square layered player head.
 *
 * Pixel sources render immediately.
 * Lookup sources are resolved asynchronously by the active runtime, publish only at a frame boundary, and ignore completions from superseded sources.
 * [loadingContent] and [failureContent] each emit zero or one root and are displayed only for their matching state.
 *
 * @receiver active owner-thread screen scope.
 * @param source immutable pixels or a platform-neutral profile lookup.
 * @param size positive logical square extent.
 * @param showHat whether the outer hat layer is painted after the face.
 * @param modifier active behavior applied to the head.
 * @param key optional stable sibling identity.
 * @param loadingContent optional zero-or-one-root loading presentation.
 * @param failureContent optional zero-or-one-root failure presentation.
 * @throws IllegalArgumentException when pixels, [size], or state callback cardinality is invalid.
 * @throws IllegalStateException when no runtime screen evaluation is active.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun UiScope.PlayerHead(
    source: PlayerSkinSource = PlayerSkinSource.CurrentPlayer,
    size: Int = 24,
    showHat: Boolean = true,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    loadingContent: (UiScope.() -> Unit)? = null,
    failureContent: (UiScope.() -> Unit)? = null,
) {
    checkUsable()
    val loading = loadingContent?.let(::buildOptionalComponentTree)
    val failure = failureContent?.let(::buildOptionalComponentTree)
    element(
        ComponentRuntimeBridge.current().playerHead(
            source,
            size,
            showHat,
            loading,
            failure,
            modifier,
            key,
        ),
    )
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
    checkUsable()
    element(ComponentRuntimeBridge.current().text(text, style, modifier, key))
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
 * Emits one 200 by 20 single-line text field.
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
 * Emits one fixed-height profile-backed pointer button.
 *
 * Reusable actions are supplied through pointer modifiers, so the component owns only appearance, sizing, and semantics.
 *
 * @receiver active owner-thread screen scope.
 * @param label unresolved button label.
 * @param width requested logical width.
 * @param enabled whether enabled appearance and semantics are used.
 * @param modifier active behavior including pointer actions.
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
 * Emits one literal fixed-height profile-backed pointer button.
 *
 * @receiver active owner-thread screen scope.
 * @param label literal converted to [UiText.Literal].
 * @param width requested logical width.
 * @param enabled whether enabled appearance and semantics are used.
 * @param modifier active behavior including pointer actions.
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
 * Pointer actions remain ordinary modifier behavior.
 *
 * @receiver active owner-thread screen scope.
 * @param label unresolved tab label.
 * @param selected externally owned selected state.
 * @param width requested logical width.
 * @param enabled whether enabled appearance, hover, and semantics are used.
 * @param indicator selected-state presentation.
 * @param modifier active behavior including pointer actions.
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
 * Literal overload of [Tab] with the same ownership and failure contract.
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
public fun UiScope.Scroll(
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    scrollRate: Int = 9,
    content: UiScope.() -> Unit,
) {
    checkUsable()
    require(0 < scrollRate) { "Scroll rate must be positive." }
    val child = buildComponentTree(content)
    element(ComponentRuntimeBridge.current().scroll(child, scrollRate, modifier, key))
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
