@file:Suppress("DEPRECATION") // The shipped showcase factories exercise compatibility entry points alongside the common public API.

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.createButtonShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createCanvasShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createCheckboxShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createColumnShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createCycleButtonShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createFlowRowShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createGridShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createImageShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createLoadingIndicatorShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createObserveShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createPlayerHeadShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createProgressBarShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createRowShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createScrollAreaShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createScrollbarShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSelectionListShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSliderShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSlotShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createSpacerShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createStackShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTabShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTextAreaShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTextFieldShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTextShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createTiledImageShowcaseScreenDefinition
import dev.s7a.strata.integration.minecraft.fabric.createVirtualListShowcaseScreenDefinition
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Real compiled showcase workloads; synthetic profile assets are declared separately from native font/resource evidence.
 * Every case uses the same definition factory as the shipped documentation and Fabric parity tests.
 */
public enum class ComponentWorkload {
    /**
     * Measures the shipped Row declaration.
     */
    Row,

    /**
     * Measures the shipped FlowRow declaration.
     */
    FlowRow,

    /**
     * Measures the shipped Column declaration.
     */
    Column,

    /**
     * Measures the shipped Stack declaration.
     */
    Stack,

    /**
     * Measures the shipped Grid declaration.
     */
    Grid,

    /**
     * Measures the shipped Spacer declaration.
     */
    Spacer,

    /**
     * Measures the shipped Observe declaration.
     */
    Observe,

    /**
     * Measures the shipped Text declaration.
     */
    Text,

    /**
     * Measures the shipped TextField declaration.
     */
    TextField,

    /**
     * Measures the shipped TextArea declaration.
     */
    TextArea,

    /**
     * Measures the shipped Button declaration.
     */
    Button,

    /**
     * Measures the shipped Checkbox declaration.
     */
    Checkbox,

    /**
     * Measures the shipped CycleButton declaration.
     */
    CycleButton,

    /**
     * Measures the shipped Slider declaration.
     */
    Slider,

    /**
     * Measures the shipped Tab declaration.
     */
    Tab,

    /**
     * Measures the shipped ScrollArea declaration.
     */
    ScrollArea,

    /**
     * Measures the shipped Scrollbar declaration.
     */
    Scrollbar,

    /**
     * Measures the shipped VirtualList declaration.
     */
    VirtualList,

    /**
     * Measures the shipped SelectionList declaration.
     */
    SelectionList,

    /**
     * Measures the shipped Image declaration.
     */
    Image,

    /**
     * Measures the shipped Canvas declaration.
     */
    Canvas,

    /**
     * Measures the shipped TiledImage declaration.
     */
    TiledImage,

    /**
     * Measures the shipped Slot declaration.
     */
    Slot,

    /**
     * Measures the shipped PlayerHead declaration.
     */
    PlayerHead,

    /**
     * Measures the shipped LoadingIndicator declaration.
     */
    LoadingIndicator,

    /**
     * Measures the shipped ProgressBar declaration.
     */
    ProgressBar,

    ;

    /**
     * Transfers the canonical declaration once and supplies loose child constraints for fixed-size components.
     * Portable and browser hosts use this same wrapper during initial display, resizing and terminal release.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    public fun uiDefinition(): UiDefinition {
        val payload = definition().transfer()
        return UiDefinition(payload.title, pausesGame = payload.pausesGame) { Stack { payload.content(this) } }
    }

    /**
     * Creates a fresh one-shot declaration; no host or previous frame is shared between lifetimes.
     */
    @Suppress("CyclomaticComplexMethod") // Exhaustive dispatch keeps additions reviewable rather than hiding missing fixtures behind reflection.
    public fun definition(): ScreenDefinition =
        when (this) {
            Row -> createRowShowcaseScreenDefinition()
            FlowRow -> createFlowRowShowcaseScreenDefinition()
            Column -> createColumnShowcaseScreenDefinition()
            Stack -> createStackShowcaseScreenDefinition()
            Grid -> createGridShowcaseScreenDefinition()
            Spacer -> createSpacerShowcaseScreenDefinition()
            Observe -> createObserveShowcaseScreenDefinition()
            Text -> createTextShowcaseScreenDefinition()
            TextField -> createTextFieldShowcaseScreenDefinition()
            TextArea -> createTextAreaShowcaseScreenDefinition()
            Button -> createButtonShowcaseScreenDefinition()
            Checkbox -> createCheckboxShowcaseScreenDefinition()
            CycleButton -> createCycleButtonShowcaseScreenDefinition()
            Slider -> createSliderShowcaseScreenDefinition()
            Tab -> createTabShowcaseScreenDefinition()
            ScrollArea -> createScrollAreaShowcaseScreenDefinition()
            Scrollbar -> createScrollbarShowcaseScreenDefinition()
            VirtualList -> createVirtualListShowcaseScreenDefinition()
            SelectionList -> createSelectionListShowcaseScreenDefinition()
            Image -> createImageShowcaseScreenDefinition(ImageSource.Pixels(createDrawImage(IntSize(64, 64), IntArray(4096) { 0xFF426789.toInt() })))
            Canvas -> createCanvasShowcaseScreenDefinition()
            TiledImage -> createTiledImageShowcaseScreenDefinition()
            Slot -> createSlotShowcaseScreenDefinition()
            PlayerHead -> createPlayerHeadShowcaseScreenDefinition(PlayerSkinSource.Pixels(createDrawImage(IntSize(64, 64), IntArray(4096) { 0xFF426789.toInt() })))
            LoadingIndicator -> createLoadingIndicatorShowcaseScreenDefinition()
            ProgressBar -> createProgressBarShowcaseScreenDefinition()
        }
}
