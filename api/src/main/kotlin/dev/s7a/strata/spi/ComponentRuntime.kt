package dev.s7a.strata.spi

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.TabSelectionIndicator
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.text.UiText

/**
 * Privileged platform implementation of profile-backed standard components.
 *
 * The API module retains only this platform-neutral contract while a runtime adapter resolves profile assets, synchronized bindings, and retained implementation nodes during one dynamically scoped screen evaluation.
 * Every method runs synchronously on the evaluation owner thread and must not retain a callback-lifetime scope.
 * Returned immutable elements and modifier chains own any retained snapshots or bindings; the caller owns those returned descriptions and the retained tree later owns their lifecycle resources.
 * Validation, resolution, and arithmetic failures propagate unchanged without emitting a partial description.
 */
@Suppress("TooManyFunctions")
@InternalStrataRuntimeApi
public interface ComponentRuntime {
    /**
     * Captures an owner-thread evaluator for content constructed after this callback-lifetime runtime closes.
     *
     * @return retained evaluator owning the immutable profile snapshot required by deferred content.
     */
    public fun retainEvaluator(): ComponentEvaluator

    /**
     * Adds one profile-backed delayed tooltip behavior.
     */
    public fun tooltip(
        modifier: Modifier,
        text: UiText,
        delayMillis: Long,
    ): Modifier

    /**
     * Creates one profile-backed determinate progress bar.
     */
    public fun progressBar(
        progress: Double,
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed discrete loading animation.
     */
    public fun loadingIndicator(
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed Checkbox with caller-owned selected state.
     *
     * @param label unresolved visible and semantic label.
     * @param state caller-owned owner-thread selected state.
     * @param width maximum fixed logical width.
     * @param enabled whether input and enabled semantics are active.
     * @param modifier active behavior including typed component actions.
     * @param key optional stable sibling identity.
     * @return immutable retained description referencing but not owning [state].
     */
    public fun checkbox(
        label: UiText,
        state: CheckboxState,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed finite-option CycleButton.
     */
    public fun cycleButton(
        state: CycleButtonState<*>,
        labels: List<UiText>,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed horizontal Slider.
     */
    public fun slider(
        label: UiText,
        state: SliderState,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed text element.
     *
     * @param text unresolved text retained for drawing and semantics.
     * @param style profile color and shadow policy.
     * @param modifier active behavior around the element.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description owned by the caller.
     */
    public fun text(
        text: UiText,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed pointer button element.
     *
     * @param label unresolved label retained for drawing and semantics.
     * @param width fixed logical button width.
     * @param enabled whether enabled pixels and semantics are used.
     * @param modifier active behavior, including caller-owned actions.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description owned by the caller.
     */
    public fun button(
        label: UiText,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed text-field element observing [state].
     *
     * @param state caller-owned owner-thread text state.
     * @param size fixed logical field size.
     * @param enabled whether editing and enabled pixels are active.
     * @param style profile text policy.
     * @param modifier active behavior around the field.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description that references but does not own [state].
     */
    public fun textField(
        state: TextFieldState,
        size: IntSize,
        enabled: Boolean,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed tab element with an optional selected custom indicator root.
     *
     * @param label unresolved label retained for drawing and semantics.
     * @param selected externally owned selected state.
     * @param width fixed logical tab width.
     * @param enabled whether enabled pixels and semantics are used.
     * @param indicator selected-state presentation policy.
     * @param customIndicator optional caller-owned selected indicator description.
     * @param modifier active behavior, including caller-owned actions.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description owned by the caller.
     */
    public fun tab(
        label: UiText,
        selected: Boolean,
        width: Int,
        enabled: Boolean,
        indicator: TabSelectionIndicator,
        customIndicator: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one ordinary or synchronized profile-backed slot element.
     *
     * @param binding optional declarative locator resolved against the active server menu.
     * @param highlightable whether pointer hover paints the profile highlight.
     * @param item optional portable item-visual child used when no platform item is bound.
     * @param modifier active behavior around the slot.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description whose node owns any resolved binding lifetime.
     */
    public fun slot(
        binding: SlotBinding?,
        highlightable: Boolean,
        item: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one profile-backed scroll viewport around exactly one [content] root.
     *
     * @param content exact caller-owned content root.
     * @param scrollRate positive logical wheel displacement multiplier.
     * @param modifier active behavior around the clipped viewport.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description owned by the caller.
     */
    public fun scrollArea(
        state: ScrollState,
        content: Element,
        scrollRate: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one independently placed profile-backed vertical scrollbar.
     *
     * @param state caller-owned position shared with one scroll area.
     * @param modifier active behavior and layout constraints around the scrollbar.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description referencing but not owning [state].
     */
    public fun scrollbar(
        state: ScrollState,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one nearest-sampled image after resolving [source] through the active resource manager when necessary.
     *
     * @param source immutable pixels or structural resource identifier.
     * @param sourceRegion optional half-open source crop.
     * @param size optional logical destination size.
     * @param modifier active behavior around the image.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description owning its resolved pixel snapshot.
     */
    public fun image(
        source: ImageSource,
        sourceRegion: IntRect?,
        size: IntSize?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Creates one player-head element and defers any asynchronous lookup represented by [source] to node attachment.
     *
     * @param source detached pixels or a structural player lookup.
     * @param size positive logical head size.
     * @param showHat whether the outer skin layer is painted.
     * @param loading optional root shown while an asynchronous lookup is pending.
     * @param failure optional root shown after lookup failure.
     * @param modifier active behavior around the head.
     * @param key optional stable sibling identity.
     * @return an immutable retained-element description whose node owns any lookup lifetime.
     */
    public fun playerHead(
        source: PlayerSkinSource,
        size: Int,
        showHat: Boolean,
        loading: Element?,
        failure: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element

    /**
     * Appends one nearest-sampled image background resolved from [source].
     *
     * @param modifier existing ordered modifier chain.
     * @param source immutable pixels or structural resource identifier.
     * @param scale mapping from source pixels to measured bounds.
     * @return [modifier] with one active background behavior appended.
     */
    public fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        scale: ImageScale,
    ): Modifier

    /**
     * Appends one nine-slice image background resolved from [source].
     *
     * @param modifier existing ordered modifier chain.
     * @param source immutable pixels or structural resource identifier.
     * @param border source and destination border widths.
     * @param centerMode expandable-segment mapping policy.
     * @return [modifier] with one active background behavior appended.
     */
    public fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): Modifier

    /**
     * Appends the active profile's menu background behavior.
     *
     * @param modifier existing ordered modifier chain.
     * @return [modifier] with one profile snapshot background behavior appended.
     */
    public fun menuBackground(modifier: Modifier): Modifier

    /**
     * Appends the active profile's generic-container background and sizing behavior.
     *
     * @param modifier existing ordered modifier chain.
     * @param rows supported logical container row count.
     * @return [modifier] with one profile snapshot background behavior appended.
     */
    public fun containerBackground(
        modifier: Modifier,
        rows: Int,
    ): Modifier
}
