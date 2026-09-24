@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

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
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteProperties.encode
import dev.s7a.strata.runtime.remote.RemoteProperties.optional
import dev.s7a.strata.runtime.remote.RemoteProperties.record
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText

/**
 * Owner-thread declaration runtime for server-owned screens using the existing component DSL.
 * It retains state and callbacks while projecting only portable properties; fonts and native widgets remain client-owned.
 */
@Suppress("TooManyFunctions") // Implements the complete component-runtime dispatch boundary.
public class RemoteComponentRuntime : ComponentRuntime {
    private val owner = RuntimeExecutionOwner.current()

    /**
     * Evaluates one root with this remote runtime, including deferred Observe and state-component content.
     */
    public fun evaluate(content: UiScope.() -> Unit): Element {
        check(RuntimeExecutionOwner.current() == owner) { "Remote component evaluation belongs to another execution owner." }
        return ComponentRuntimeBridge.evaluate(this, content)
    }

    override fun retainEvaluator(): ComponentEvaluator = ComponentEvaluator(::evaluate)

    override fun menuBackground(modifier: Modifier): Modifier = modifier.then(RemoteProfileModifier(RemoteProfileComponent.MenuBackground, record()))

    override fun tooltip(
        modifier: Modifier,
        text: UiText,
        delayMillis: Long,
    ): Modifier = modifier.then(RemoteProfileModifier(RemoteProfileComponent.Tooltip, record(encode(text), ProjectionValue.Integer(delayMillis))))

    override fun progressBar(
        progress: Double,
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.ProgressBar, modifier, key) { _ ->
            record(encode(progress), encode(size))
        }

    override fun loadingIndicator(
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.LoadingIndicator, modifier, key) { _ ->
            record(encode(size))
        }

    override fun checkbox(
        label: UiText,
        state: CheckboxState,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Checkbox, modifier, key, enabled = enabled) { scope ->
            record(encode(label), RemoteFormBindings.checkbox(scope, state, modifier), encode(width), encode(enabled))
        }

    override fun cycleButton(
        state: CycleButtonState<*>,
        labels: List<UiText>,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.CycleButton, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.cycle(scope, state, modifier), ProjectionValue.Sequence(labels.map(::encode)), encode(width), encode(enabled))
        }

    override fun slider(
        label: UiText,
        state: SliderState,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Slider, modifier, key, enabled = enabled) { scope ->
            record(encode(label), RemoteFormBindings.slider(scope, state, modifier), encode(state.range.start), encode(state.range.endInclusive), encode(state.steps), encode(width), encode(enabled))
        }

    override fun text(
        text: UiText,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Text, modifier, key) { _ ->
            record(encode(text), encode(TextLayout.SingleLine), encode(style))
        }

    override fun text(
        text: UiText,
        layout: TextLayout,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Text, modifier, key) { _ ->
            record(encode(text), encode(layout), encode(style))
        }

    override fun button(
        label: UiText,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Button, modifier, key, enabled = enabled) { _ ->
            record(encode(label), encode(width), encode(enabled))
        }

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        enabled: Boolean,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextField, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textField(scope, state), encode(state.maxLength), encode(size), encode(TextInputAppearance.Default), encode(enabled), encode(style), ProjectionValue.Absent)
        }

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextField, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textField(scope, state), encode(state.maxLength), encode(size), encode(TextInputAppearance.Default), encode(enabled), encode(style), encode(font))
        }

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        enabled: Boolean,
        style: TextStyle,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextArea, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textArea(scope, state), encode(state.maxLength), encode(viewport), encode(TextInputAppearance.Default), encode(enabled), encode(style), ProjectionValue.Absent, encode(wrap), encode(lineSpacing), RemoteFormBindings.scroll(scope, state.scrollState))
        }

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextArea, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textArea(scope, state), encode(state.maxLength), encode(viewport), encode(TextInputAppearance.Default), encode(enabled), encode(style), encode(font), encode(wrap), encode(lineSpacing), RemoteFormBindings.scroll(scope, state.scrollState))
        }

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextField, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textField(scope, state), encode(state.maxLength), encode(size), encode(appearance), encode(enabled), encode(style), ProjectionValue.Absent)
        }

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextField, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textField(scope, state), encode(state.maxLength), encode(size), encode(appearance), encode(enabled), encode(style), encode(font))
        }

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextArea, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textArea(scope, state), encode(state.maxLength), encode(viewport), encode(appearance), encode(enabled), encode(style), ProjectionValue.Absent, encode(wrap), encode(lineSpacing), RemoteFormBindings.scroll(scope, state.scrollState))
        }

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.TextArea, modifier, key, enabled = enabled) { scope ->
            record(RemoteFormBindings.textArea(scope, state), encode(state.maxLength), encode(viewport), encode(appearance), encode(enabled), encode(style), encode(font), encode(wrap), encode(lineSpacing), RemoteFormBindings.scroll(scope, state.scrollState))
        }

    override fun tab(
        label: UiText,
        selected: Boolean,
        width: Int,
        enabled: Boolean,
        indicator: TabSelectionIndicator,
        customIndicator: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Tab, modifier, key, children = listOfNotNull(customIndicator), enabled = enabled) { _ ->
            record(encode(label), encode(selected), encode(width), encode(enabled), encode(indicator))
        }

    override fun slot(
        binding: SlotBinding?,
        highlightable: Boolean,
        item: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Slot, modifier, key, children = listOfNotNull(item)) { _ ->
            record(optional(binding, ::encode), encode(highlightable))
        }

    override fun scrollArea(
        state: ScrollState,
        content: Element,
        scrollRate: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.ScrollArea, modifier, key, children = listOf(content)) { scope ->
            record(RemoteFormBindings.scroll(scope, state), encode(scrollRate))
        }

    override fun scrollbar(
        state: ScrollState,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Scrollbar, modifier, key) { scope ->
            record(RemoteFormBindings.scroll(scope, state))
        }

    override fun image(
        source: ImageSource,
        sourceRegion: IntRect?,
        size: IntSize?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.Image, modifier, key) { _ ->
            record(encode(source), optional(sourceRegion, ::encode), optional(size, ::encode))
        }

    override fun playerHead(
        source: PlayerSkinSource,
        size: Int,
        showHat: Boolean,
        loading: Element?,
        failure: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        RemoteProfileElement(RemoteProfileComponent.PlayerHead, modifier, key, children = listOfNotNull(loading, failure)) { _ ->
            record(encode(source), encode(size), encode(showHat), encode(loading != null), encode(failure != null))
        }

    override fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        scale: ImageScale,
    ): Modifier = modifier.then(RemoteProfileModifier(RemoteProfileComponent.ImageBackground, record(encode(source), encode(scale))))

    override fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): Modifier = modifier.then(RemoteProfileModifier(RemoteProfileComponent.NineSliceBackground, record(encode(source), encode(border), encode(centerMode))))

    override fun containerBackground(
        modifier: Modifier,
        rows: Int,
    ): Modifier = modifier.then(RemoteProfileModifier(RemoteProfileComponent.ContainerBackground, record(encode(rows))))
}
