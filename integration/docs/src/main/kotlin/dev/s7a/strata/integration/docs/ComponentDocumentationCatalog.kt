package dev.s7a.strata.integration.docs

/**
 * Canonical prose catalog shared by the generated showcase and public Strata skill.
 *
 * Every standard component identity must be handled explicitly so API additions cannot silently enter either document without a generality and usage review.
 */
internal object ComponentDocumentationCatalog {
    /**
     * Describes the focused responsibility of one standard component.
     *
     * @param component typed standard component identity.
     * @return concise responsibility and rendering contract.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun summary(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "Row places an ordered sibling sequence on one horizontal main axis, with typed arrangement, spacing, default vertical alignment, and direct-child overrides."
            DocumentedComponent.FlowRow -> "FlowRow wraps an ordered sibling sequence at the available width, measures each child against the full parent maximums, and arranges each row independently. It serves action-button groups and option groups without encoding either domain."
            DocumentedComponent.Column -> "Column places an ordered sibling sequence on one vertical main axis, with typed arrangement, spacing, default horizontal alignment, and direct-child overrides."
            DocumentedComponent.Stack -> "Stack is the explicit overlay primitive: children share one content rectangle, receive two-axis alignment, and paint in declaration order. It is not a generic div-like container."
            DocumentedComponent.Grid -> "Grid assigns children row-major to a fixed column count, measures each column and row from its largest member, and supports an incomplete final row without placeholders."
            DocumentedComponent.Spacer -> "Spacer is an empty measurable primitive for genuine visual separators, connectors, and weighted empty regions; it carries no screen-specific meaning."
            DocumentedComponent.Slot -> "Slot reproduces the native 18 by 18 hit region and 24 by 24 back-item-front highlight order; its binding overload polls real ItemStack state and delegates interaction through Minecraft's active container menu."
            DocumentedComponent.Text -> "Text renders Unicode literals and composed text using the active profile's font resources, glyph advances, shadow layer, foreground layer, and baseline. Explicit `TextLayout.Multiline` adds hard line breaks, wrapping, line limits, and clip or ellipsis overflow; the existing overload remains single-line. Glyph availability follows the selected resource pack."
            DocumentedComponent.TextField -> "TextField reproduces the 200 by 20 Minecraft EditBox sprites, text origin, glyph colors, owner-thread value state, and focus, with Unicode scalar editing and inline IME composition."
            DocumentedComponent.TextArea -> "TextArea edits one multiline value inside an explicit viewport with Unicode scalar navigation, inline IME composition, and independent vertical scrolling. It serves both note editing and message drafts without encoding an application model."
            DocumentedComponent.Button -> "Button renders verified fixed-height Minecraft sprite and label states, including the native 150- and 200-pixel widths, while reusable input actions live in modifiers."
            DocumentedComponent.Checkbox -> "Checkbox reproduces the verified 20-pixel Minecraft checkbox surface, label spacing, focused input, checked semantics, and caller-owned boolean state."
            DocumentedComponent.CycleButton -> "CycleButton reuses the verified button surface for a finite generic option sequence with forward, backward, wheel, and keyboard navigation."
            DocumentedComponent.Slider -> "Slider reproduces Minecraft's profile-backed track and handle while normalizing finite numeric ranges and optional discrete steps in caller-owned state."
            DocumentedComponent.Tab -> "Tab combines the verified button surface with external selection semantics and a reusable underline or caller-defined selected indicator, without encoding a particular screen's tab model."
            DocumentedComponent.ScrollArea -> "ScrollArea reproduces the verified Minecraft menu-list background, clipping, separators, and wheel behavior without owning or positioning a scrollbar."
            DocumentedComponent.Scrollbar -> "Scrollbar reproduces the verified tiled track and proportional thumb while remaining an independently placed observer of shared scroll metrics."
            DocumentedComponent.VirtualList -> "VirtualList retains only visible fixed-height rows plus bounded overscan, supports prepended and appended loading, and can jump by index or stable key."
            DocumentedComponent.SelectionList -> "SelectionList adds generic caller-owned selection and typed selection-change actions to VirtualList without encoding Social, inventory, advancement, or Mod-specific rows."
            DocumentedComponent.Image -> "Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels."
            DocumentedComponent.Canvas -> "Canvas displays externally produced CPU frames or version-runtime native output in one input-passive rectangle. Decoded video and camera, filter, or custom-renderer output are independent uses; composing Image and Stack cannot provide source cutoffs, attachment lifetimes, leased GPU capture, or owned offscreen targets. The component does not implement a decoder, camera, world renderer, filter, or browser engine."
            DocumentedComponent.TiledImage -> "TiledImage presents one bounded logical raster from independently revisioned immutable tiles, selecting only the visible level and coarser fallback working set instead of joining or copying the complete image. Maps, scans, and schematics are independent uses that cannot preserve bounded subscriptions and reusable tile images through ordinary Image composition alone."
            DocumentedComponent.PlayerHead -> "PlayerHead reproduces Minecraft 26.2 face-then-hat rendering from a 64 by 64 skin. PlayerHeadScale gives every source texel an equal integer-sized square for crisp lists, profiles, scoreboards, and Mod screens; the deprecated arbitrary-size overload uses region-clamped bilinear interpolation when an exact integer scale is impossible."
            DocumentedComponent.LoadingIndicator -> "LoadingIndicator reproduces the Minecraft 26.2 friends-loading sprite as three vertical 5 by 2 cells with the native six-tick frame duration; older runtimes use the same pack-overridable path before their compatibility fallback."
            DocumentedComponent.ProgressBar -> "ProgressBar uses the reusable bundle progress border, partial fill, and completed fill with their native two-pixel nine-slice borders and exposes read-only progress semantics."
        }

    /**
     * Describes how modifiers compose with one standard component.
     *
     * @param component typed standard component identity.
     * @return component-specific modifier guidance.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun modifierGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "Sizing, padding, paint, semantics, focus, and input modifiers apply to the Row itself; `spacing` and `horizontalArrangement` express structure, while `RowScope.weight` and `RowScope.align` affect only direct children."
            DocumentedComponent.FlowRow -> "Sizing, padding, paint, semantics, focus, and input modifiers apply to the FlowRow itself. It uses its natural width unless constraints or `fillMaxWidth()` expand it; `horizontalSpacing`, `verticalSpacing`, and `horizontalArrangement` control its rows, while `FlowRowScope.align` overrides one child's vertical alignment within its row."
            DocumentedComponent.Column -> "Sizing, padding, paint, semantics, focus, and input modifiers apply to the Column itself; `spacing` and `verticalArrangement` express structure, while `ColumnScope.weight` and `ColumnScope.align` affect only direct children."
            DocumentedComponent.Stack -> "Use Stack only when children intentionally overlap. Ordinary sizing and background modifiers belong on the Stack; `StackScope.align` positions an individual overlay child without coordinate padding."
            DocumentedComponent.Grid -> "Sizing, padding, and paint modifiers apply to the Grid. Fixed columns, independent horizontal and vertical spacing, and `GridScope.align` replace repeated Row declarations and per-cell coordinate padding."
            DocumentedComponent.Spacer -> "Sizing, weight, and paint modifiers give Spacer a deliberate empty footprint, such as a separator or progress connector; ordinary parent spacing and alignment should remain layout arguments rather than placeholder children."
            DocumentedComponent.Slot -> "Sizing is native-fixed at 18 by 18. `Slots.playerInventory(index)` binds player storage, `Slots.container(index)` addresses logical storage exposed by chests, ender chests, furnaces, and custom server menus, and `Slots.activeMenu(index)` remains the raw-menu escape hatch; the optional-content overload remains portable for custom item visuals."
            DocumentedComponent.Text -> "Ordinary sizing, padding, placement, and paint modifiers compose around `Text`; multiline layout uses the available width and height. `TextWrap.None`, `Word`, or `Character`, `maxLines`, `TextOverflow.Clip` or `Ellipsis`, and `lineSpacing` control presentation without changing the original semantic label. Text content and the optional `font: ResourceId` remain typed component arguments. `UiText.withFont` also selects a font for labels and composed text; an inner selection takes precedence over an outer one."
            DocumentedComponent.TextField -> "Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing. The `font: ResourceId` overload changes metrics and drawing together, including cursor placement and horizontal scrolling."
            DocumentedComponent.TextArea -> "Place `TextArea` with ordinary layout modifiers and select its outer extent through `TextAreaViewport.Size` or `Lines`. Minecraft uses a fixed 9-pixel logical line box, optional extra line spacing, and four-pixel padding on each side. An external `Scrollbar(state.scrollState)` observes the editor's caller-owned scroll state; the editor does not insert a scrollbar or toolbar. The `font: ResourceId` overload changes layout, cursor placement, and drawing together."
            DocumentedComponent.Button -> "Pointer behavior is active modifier behavior. `onPointerEvent`, `onPress`, `onRelease`, `onMove`, `onDrag`, `onScroll`, and `onHover` can be composed without adding component-specific callback parameters."
            DocumentedComponent.Checkbox -> "Sizing and placement modifiers compose around `Checkbox`; caller-owned state and typed checked-change actions keep the reusable boolean control independent of a settings domain."
            DocumentedComponent.CycleButton -> "Sizing and placement modifiers compose around `CycleButton`; its immutable option set and typed change action remain generic rather than encoding one game's option model."
            DocumentedComponent.Slider -> "Sizing and placement modifiers compose around `Slider`; caller-owned range state and typed value-change actions remain reusable across volume, brightness, machine power, and other numeric domains."
            DocumentedComponent.Tab -> "Selection is caller-owned data, while `Underline` or `Custom` controls its reusable selected-state presentation. All pointer actions remain ordinary event modifiers, exactly as for Button and other interactive components."
            DocumentedComponent.ScrollArea -> "Ordinary sizing and placement modifiers define only the clipped viewport. The shared `ScrollState` links optional external controls without forcing a scrollbar into the component tree."
            DocumentedComponent.Scrollbar -> "Sizing and parent placement modifiers position `Scrollbar` independently from its viewport; sharing `ScrollState` is the only link required."
            DocumentedComponent.VirtualList -> "Sizing is expressed by `viewportSize`; modifier actions receive leading and trailing load requests while caller-owned state supports index, key, and boundary navigation."
            DocumentedComponent.SelectionList -> "Viewport behavior composes with typed selection actions and caller-owned selection state; row visuals remain application composition rather than a screen-specific built-in."
            DocumentedComponent.Image -> "Sizing and placement modifiers compose around `Image`; `imageBackground` paints the same immutable resource behind any layout component with typed stretch or tile mapping."
            DocumentedComponent.Canvas -> "Use an explicit positive logical `size`; the whole source stretches with nearest sampling, and changes to source pixel extent only repaint that destination. Canvas is input-passive. Compose `onCapturedPointerEvent` to forward unclamped local logical pointer coordinates, and use ordinary focus and keyboard modifiers only when the application needs them."
            DocumentedComponent.TiledImage -> "Use the explicit positive `size` as the clipped viewport, keep navigation in caller-owned `PanZoomState`, and compose `panZoom(state)` when direct drag and wheel navigation is wanted. `PanZoomFit.Contain` or `Cover` defines zoom one; ordinary paint and semantics modifiers apply to the viewport without changing tile identities."
            DocumentedComponent.PlayerHead -> "Pass `PlayerHeadScale(1)` for an 8 by 8 head, or another positive factor when every source texel should remain the same size. Sizing and placement modifiers compose around `PlayerHead`; its immutable skin argument stays separate from Social, player-list, scoreboard, profile, and Mod-specific row state."
            DocumentedComponent.LoadingIndicator -> "Sizing and placement modifiers compose around `LoadingIndicator`; explicit host frame time advances its discrete profile animation without application-owned timer state."
            DocumentedComponent.ProgressBar -> "Sizing and placement modifiers compose around `ProgressBar`; its normalized value is immutable component data while the active profile supplies resource-pack-aware fill, completed-fill, and border sprites."
        }

    /**
     * Describes the parent-scope and ownership rules of one standard component.
     *
     * @param component typed standard component identity.
     * @return parent-scope and retained ownership guidance.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun parentScopeGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "`Row` evaluates a callback-lifetime `RowScope`, emits children in declaration order, and exposes only vertical alignment and weight parent data to its direct children."
            DocumentedComponent.FlowRow -> "`FlowRow` evaluates a callback-lifetime `FlowRowScope` and exposes only vertical alignment parent data. Wrapping preserves its direct children's retained identity and focus without synthetic Row parents. It has no weight, row-count limit, implicit clipping, or truncation; with unbounded width it produces one row."
            DocumentedComponent.Column -> "`Column` evaluates a callback-lifetime `ColumnScope`, emits children in declaration order, and exposes only horizontal alignment and weight parent data to its direct children."
            DocumentedComponent.Stack -> "`Stack` evaluates a callback-lifetime `StackScope`; it measures and paints overlapping direct children in declaration order and exposes two-axis alignment parent data."
            DocumentedComponent.Grid -> "`Grid` evaluates a callback-lifetime `GridScope`; it assigns direct children row-major and exposes two-axis alignment only inside each measured cell."
            DocumentedComponent.Spacer -> "`Spacer` has no content scope or children. Its size and modifier chain alone define its retained layout and paint behavior."
            DocumentedComponent.Slot -> "`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root, while its bound overload obtains the version platform implicitly and retains no public Minecraft type."
            DocumentedComponent.Text -> "`Text` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and the component has no content callback or parent-data API. Unicode and custom fonts require a font-resource profile; the older printable-ASCII glyph builder remains a compatibility path."
            DocumentedComponent.TextField -> "`TextField` is a top-level extension on the active `UiScope`. Caller-owned `TextFieldState` owns the value and its positive UTF-16 maximum length. Movement and deletion operate on Unicode scalars, not whole grapheme clusters; preedit text remains separate until committed input arrives. The inline composition display does not reproduce Minecraft's native IME popup or platform candidate window."
            DocumentedComponent.TextArea -> "`TextArea` is a leaf extension on the active `UiScope`; one retained editor observes its owner-thread `TextAreaState`. Creating an immutable description does not attach the state, and descriptions can be reused after detachment. Simultaneous attachment with the same caller-owned state throws `IllegalStateException`. The state stores canonical LF newlines and enforces a positive UTF-16 maximum length. Soft wrapping never edits the stored value, and IME preedit remains separate until committed. `SemanticsRole.TextArea` exposes the committed text through `Semantics.value`, without typed accessibility edit actions. Selection, clipboard commands, grapheme-cluster editing, and the platform IME candidate window are outside this component's contract."
            DocumentedComponent.Button -> "`Button` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and pointer event modifiers remain valid only through their retained modifier-node lifetime."
            DocumentedComponent.Checkbox -> "`Checkbox` is a leaf extension on the active `UiScope`; `CheckboxState` is caller-owned, owner-thread confined, and may be shared with application state adapters."
            DocumentedComponent.CycleButton -> "`CycleButton` is a leaf extension on the active `UiScope`; it snapshots labels for the validated finite option set and retains no child scope."
            DocumentedComponent.Slider -> "`Slider` is a leaf extension on the active `UiScope`; `SliderState` owns normalization and quantization while the active profile owns rendering."
            DocumentedComponent.Tab -> "`Tab` is a top-level extension on the active `UiScope`. A custom selected indicator emits exactly one nested root; the selected value and event actions remain application-owned."
            DocumentedComponent.ScrollArea -> "`ScrollArea` evaluates a callback-lifetime `UiScope` that emits exactly one content root; the caller owns the linked state and may omit a scrollbar."
            DocumentedComponent.Scrollbar -> "`Scrollbar` is an independent leaf in any surrounding layout. It observes caller-owned `ScrollState` and releases that observation when its retained node is disposed."
            DocumentedComponent.VirtualList -> "`VirtualList` evaluates row callbacks only for visible rows plus bounded overscan; stable keys preserve retained identity while the caller owns source and navigation state."
            DocumentedComponent.SelectionList -> "`SelectionList` wraps visible virtual rows with generic selection semantics and press handling while leaving each row's single content root to the caller."
            DocumentedComponent.Image -> "`Image` is a top-level extension on the active `UiScope`. It retains detached pixels rather than a Minecraft resource object, so the Fabric loader may resolve a resource-pack replacement before the description is built."
            DocumentedComponent.Canvas -> "`Canvas` is a leaf extension with no content scope or parent-data API. `canvasSource(image)` retains immutable CPU pixels, while `canvasSource(frames)` observes `StateSource<DrawImage>` through owner-thread frame cutoffs. Each attachment owns its binding; replacement, detachment, and close stop that binding without closing the externally owned source. Native sources require the matching versioned runtime and do not read back pixels during normal presentation. Native headless capture requires an immutable snapshot of the same committed generation, physical extent, and top-left orientation; a missing or mismatched snapshot fails before any output."
            DocumentedComponent.TiledImage -> "`TiledImage` evaluates a callback-lifetime `TiledImageScope`; each fixed-size direct child uses `atContentPosition` with either a fixed coordinate or a `StateSource<DoubleOffset>` committed at frame cutoff. Revisioned marker movement changes only overlay placement while tiles retain their identities. The source instance identifies immutable exactly representable bounds and level geometry and owns every tile history. One retained attachment owns its bounded subscriptions and derived presentation cache, closes them on replacement or detach, and never closes the source or mutates returned images."
            DocumentedComponent.PlayerHead -> "`PlayerHead` is a top-level extension on the active `UiScope`. `Pixels` retains a detached immutable skin, while `CurrentPlayer`, `Name`, and `Uuid` remain structural asynchronous lookups deferred to node attachment; the retained node owns and releases that lookup lifetime."
            DocumentedComponent.LoadingIndicator -> "`LoadingIndicator` is a top-level extension on the active `UiScope`. The Fabric host supplies one timestamp per native render pass and the retained node invalidates only when its discrete animation cell changes."
            DocumentedComponent.ProgressBar -> "`ProgressBar` is a top-level extension on the active `UiScope`. The implicit profile resolves the active resource pack before retaining immutable sprite pixels."
        }
}
