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
            DocumentedComponent.Observe -> "Observe recomputes one region from up to 22 typed StateSource values, for conditional children or layout/style changes. Compatible keyed nodes survive reevaluation."
            DocumentedComponent.Row -> "Row places an ordered sibling sequence on one horizontal main axis, with typed arrangement, spacing, default vertical alignment, and direct-child overrides."
            DocumentedComponent.FlowRow -> "FlowRow wraps an ordered sibling sequence at the available width and arranges each row independently."
            DocumentedComponent.Column -> "Column places an ordered sibling sequence on one vertical main axis, with typed arrangement, spacing, default horizontal alignment, and direct-child overrides."
            DocumentedComponent.Stack -> "Stack is the explicit overlay primitive: children share one content rectangle, receive two-axis alignment, and paint in declaration order. It is not a generic div-like container."
            DocumentedComponent.Grid -> "Grid assigns children row-major to a fixed column count, measures each column and row from its largest member, and supports an incomplete final row without placeholders."
            DocumentedComponent.Spacer -> "Spacer is an empty measurable primitive for genuine visual separators, connectors, and weighted empty regions; it carries no screen-specific meaning."
            DocumentedComponent.Slot -> "Slot reproduces the native 18 by 18 hit region and 24 by 24 back-item-front highlight order; its binding overload polls real ItemStack state and delegates interaction through Minecraft's active container menu."
            DocumentedComponent.Text -> "Text renders Unicode literals and composed text from the selected resource pack. Use `TextLayout.Multiline` for hard breaks, wrapping, line limits, and overflow."
            DocumentedComponent.TextField -> "TextField uses the 200 by 20 Minecraft EditBox sprites with Unicode scalar editing and inline IME composition."
            DocumentedComponent.TextArea -> "TextArea supports note editing and message drafts with multiline scalar navigation, inline IME composition, and independent scrolling."
            DocumentedComponent.Button -> "Button renders a label and enabled state. It owns no implicit focus or activation; reusable input actions live in modifiers."
            DocumentedComponent.Checkbox -> "Checkbox reproduces the verified 20-pixel Minecraft checkbox surface, label spacing, focused input, checked semantics, and caller-owned boolean state."
            DocumentedComponent.CycleButton -> "CycleButton reuses the verified button surface for a finite generic option sequence with forward, backward, wheel, and keyboard navigation."
            DocumentedComponent.Slider -> "Slider reproduces Minecraft's profile-backed track and handle while normalizing finite numeric ranges and optional discrete steps in caller-owned state."
            DocumentedComponent.Tab -> "Tab combines the verified button surface with external selection semantics and a reusable underline or caller-defined selected indicator, without encoding a particular screen's tab model or owning implicit focus and activation."
            DocumentedComponent.ScrollArea -> "ScrollArea reproduces the verified Minecraft menu-list background, clipping, separators, and wheel behavior without owning or positioning a scrollbar."
            DocumentedComponent.Scrollbar -> "Scrollbar reproduces the verified tiled track and proportional thumb while remaining an independently placed observer of shared scroll metrics."
            DocumentedComponent.VirtualList -> "VirtualList retains only visible fixed-height rows plus bounded overscan, supports prepended and appended loading, and can jump by index or stable key."
            DocumentedComponent.SelectionList -> "SelectionList adds caller-owned selection and typed change actions to virtual rows."
            DocumentedComponent.Image -> "Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels."
            DocumentedComponent.Canvas -> "Canvas displays external CPU frames or native drawing in an input-passive rectangle. The application owns decoding and rendering; Strata owns placement and attachment lifetimes."
            DocumentedComponent.TiledImage -> "TiledImage displays maps, scans, or schematics from independently revisioned immutable tiles, observing only a bounded visible set and coarser fallbacks."
            DocumentedComponent.PlayerHead -> "PlayerHead provides face-then-hat rendering from a skin. Prefer `PlayerHeadScale` for equal integer-sized texels; the deprecated arbitrary-size overload interpolates when needed."
            DocumentedComponent.LoadingIndicator -> "LoadingIndicator displays the profile's discrete loading animation using host frame time."
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
            DocumentedComponent.Observe -> "Observe is one child of its containing layout. Apply weight, alignment, sizing, and event modifiers to the region itself. Content emits zero or one root; use an inner Row or Column for multiple children. Empty content has zero natural size and still obeys incoming constraints."
            DocumentedComponent.Row -> "Apply sizing, padding, backgrounds, and input to the Row. Use `spacing` and `horizontalArrangement` for sibling structure."
            DocumentedComponent.FlowRow -> "Use `fillMaxWidth()` to arrange rows across the available width; `horizontalSpacing` and `verticalSpacing` set gaps. `FlowRowScope.align` overrides one child's vertical alignment."
            DocumentedComponent.Column -> "Apply sizing, padding, backgrounds, and input to the Column. Use `spacing` and `verticalArrangement` for sibling structure."
            DocumentedComponent.Stack -> "Use Stack only when children intentionally overlap. Ordinary sizing and background modifiers belong on the Stack; `StackScope.align` positions an individual overlay child without coordinate padding."
            DocumentedComponent.Grid -> "Sizing, padding, and paint modifiers apply to the Grid. Fixed columns, independent horizontal and vertical spacing, and `GridScope.align` replace repeated Row declarations and per-cell coordinate padding."
            DocumentedComponent.Spacer -> "Sizing, weight, and paint modifiers give Spacer a deliberate empty footprint, such as a separator or progress connector; ordinary parent spacing and alignment should remain layout arguments rather than placeholder children."
            DocumentedComponent.Slot -> "Sizing is native-fixed at 18 by 18. `Slots.playerInventory(index)` binds player storage, `Slots.container(index)` addresses logical storage exposed by chests, ender chests, furnaces, and custom server menus, and `Slots.activeMenu(index)` remains the raw-menu escape hatch; the optional-content overload remains portable for custom item visuals."
            DocumentedComponent.Text -> "Use `TextLayout.Multiline` to fit reserved text rectangles. Set wrapping, overflow, and line spacing on Text; `UiText.withFont` selects fonts within composed labels, with inner selections taking precedence."
            DocumentedComponent.TextField -> "Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing. The `font: ResourceId` overload changes metrics and drawing together, including cursor placement and horizontal scrolling."
            DocumentedComponent.TextArea -> "Choose `TextAreaViewport.Size` or `Lines`; the fixed 9-pixel logical line box has optional extra spacing and four-pixel frame insets. Link an optional `Scrollbar(state.scrollState)` independently."
            DocumentedComponent.Button -> "Compose `onActivate` with the component's enabled state when a primary pointer press and each focused Enter or Space press represent the same action; false adds no input or focus node. `onPointerEvent`, `onPress`, `onRelease`, `onMove`, `onDrag`, `onScroll`, and `onHover` remain available for pointer-specific behavior without component callback parameters."
            DocumentedComponent.Checkbox -> "Use typed checked-change modifiers with caller-owned boolean state."
            DocumentedComponent.CycleButton -> "Use typed change modifiers with an immutable finite option set."
            DocumentedComponent.Slider -> "Use typed value-change modifiers with caller-owned range and step state."
            DocumentedComponent.Tab -> "Selection is caller-owned data, while `Underline` or `Custom` controls its reusable selected-state presentation. Compose `onActivate(enabled)` for shared primary-pointer and focused Enter-or-Space activation; raw pointer actions remain ordinary event modifiers."
            DocumentedComponent.ScrollArea -> "Ordinary sizing and placement modifiers define only the clipped viewport. The shared `ScrollState` links optional external controls without forcing a scrollbar into the component tree."
            DocumentedComponent.Scrollbar -> "Sizing and parent placement modifiers position `Scrollbar` independently from its viewport; sharing `ScrollState` is the only link required."
            DocumentedComponent.VirtualList -> "Sizing is expressed by `viewportSize`; modifier actions receive leading and trailing load requests while caller-owned state supports index, key, and boundary navigation."
            DocumentedComponent.SelectionList -> "Viewport behavior composes with typed selection actions and caller-owned selection state; row visuals remain application composition rather than a screen-specific built-in."
            DocumentedComponent.Image -> "Use Image as a child; use `imageBackground` to paint the same resource behind a container."
            DocumentedComponent.Canvas -> "Use an explicit positive logical `size`; the whole source stretches with nearest sampling, and changes to source pixel extent only repaint that destination. Canvas is input-passive. Compose `onCapturedPointerEvent` to forward unclamped local logical pointer coordinates, and use ordinary focus and keyboard modifiers only when the application needs them."
            DocumentedComponent.TiledImage -> "Use the explicit positive `size` as the clipped viewport, keep navigation in caller-owned `PanZoomState`, and compose `panZoom(state)` when direct drag and wheel navigation is wanted. `PanZoomFit.Contain` or `Cover` defines zoom one; ordinary paint and semantics modifiers apply to the viewport without changing tile identities."
            DocumentedComponent.PlayerHead -> "Use `PlayerHeadScale(1)` for an 8 by 8 head, or a larger positive factor. Apply ordinary placement modifiers around it."
            DocumentedComponent.LoadingIndicator -> "Position it with layout modifiers; host time advances the animation without application timer state."
            DocumentedComponent.ProgressBar -> "Position it with layout modifiers; the active profile supplies fill, completed-fill, and border sprites."
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
            DocumentedComponent.Observe -> "Content emits zero or one root on the owner thread. Keep editing and navigation state outside reevaluation."
            DocumentedComponent.Row -> "`RowScope` exposes vertical `align` and `weight` for direct children; the scope expires with its callback."
            DocumentedComponent.FlowRow -> "`FlowRow` evaluates a callback-lifetime `FlowRowScope` and exposes only vertical alignment parent data. Wrapping preserves its direct children's retained identity and focus without synthetic Row parents. It has no weight, row-count limit, implicit clipping, or truncation; with unbounded width it produces one row."
            DocumentedComponent.Column -> "`ColumnScope` exposes horizontal `align` and `weight` for direct children; the scope expires with its callback."
            DocumentedComponent.Stack -> "`StackScope.align` positions direct overlays, which paint in declaration order."
            DocumentedComponent.Grid -> "`GridScope.align` positions a direct child within its measured cell."
            DocumentedComponent.Spacer -> "No children or content scope; modifiers define its empty footprint."
            DocumentedComponent.Slot -> "`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root, while its bound overload obtains the version platform implicitly and retains no public Minecraft type."
            DocumentedComponent.Text -> "No children. Fixed and source-backed labels share font and geometry rules; source values commit at frame boundaries."
            DocumentedComponent.TextField -> "Keep caller-owned `TextFieldState` on its owner thread with a positive UTF-16 maximum length. Editing uses scalars, not grapheme clusters. Preedit stays separate until committed; it does not reproduce Minecraft's native IME popup."
            DocumentedComponent.TextArea -> "One attached editor per owner-thread `TextAreaState`; reuse after detach is allowed. State stores LF text and a positive UTF-16 limit. `SemanticsRole.TextArea` and `Semantics.value` expose committed text; selection, clipboard, typed accessibility edit actions, and grapheme editing are unavailable."
            DocumentedComponent.Button -> "The screen runtime installs its selected Minecraft profile only for the definition callback. Button has no child scope."
            DocumentedComponent.Checkbox -> "No children. Retain `CheckboxState` on its owner thread."
            DocumentedComponent.CycleButton -> "No children. Labels are snapshotted for the validated option set."
            DocumentedComponent.Slider -> "No children. `SliderState` owns normalization and quantization."
            DocumentedComponent.Tab -> "`Tab` is a top-level extension on the active `UiScope`. A custom selected indicator emits exactly one nested root; the selected value and event actions remain application-owned."
            DocumentedComponent.ScrollArea -> "`ScrollArea` evaluates a callback-lifetime `UiScope` that emits exactly one content root; the caller owns the linked state and may omit a scrollbar."
            DocumentedComponent.Scrollbar -> "An independent leaf observing caller-owned `ScrollState`; disposal releases that observation."
            DocumentedComponent.VirtualList -> "`VirtualList` evaluates row callbacks only for visible rows plus bounded overscan; stable keys preserve retained identity while the caller owns source and navigation state."
            DocumentedComponent.SelectionList -> "`SelectionList` wraps visible virtual rows with generic selection semantics and press handling while leaving each row's single content root to the caller."
            DocumentedComponent.Image -> "No children. The description retains detached pixels, not a mapped Minecraft resource."
            DocumentedComponent.Canvas -> "No children. Each attachment observes its source without owning it. Native factories need the matching runtime; portable capture requires a snapshot matching the presented generation and extent. See the Canvas guide for lease and capture contracts."
            DocumentedComponent.TiledImage -> "`TiledImageScope.atContentPosition` anchors fixed-size children using fixed or source-backed coordinates. Marker movement changes placement without changing tile identities. The source fixes bounds and levels; attachment replacement or detach closes observations, not the source."
            DocumentedComponent.PlayerHead -> "`Pixels` retains a detached skin. `CurrentPlayer`, `Name`, and `Uuid` defer asynchronous lookup to attachment, which owns its release."
            DocumentedComponent.LoadingIndicator -> "No children. The retained node invalidates only when its animation cell changes."
            DocumentedComponent.ProgressBar -> "No children. The profile resolves resource-pack sprites before retaining their pixels."
        }
}
