# Share a UI with the browser

Keep the application definition and its caller-owned state in a Kotlin Multiplatform common source set.
Compile it against `dev.s7a.strata:strata-api-multiplatform`.
Add `dev.s7a.strata:strata-runtime-web` to the browser source set and keep both dependencies on the same Strata release.
Minecraft opening, browser resource preparation, network clients and authentication belong in their respective hosts.
The browser does not need a replacement definition.

The browser host supports native Text, TextField, TextArea, buttons, portable Canvas commands, images, image backgrounds, nine-slice backgrounds, PlayerHead, ScrollArea, Scrollbar, VirtualList, SelectionList and the common layout and input modifiers.
TiledImage, PanZoomState, `panZoom` and `atContentPosition` use the shared retained implementations.
Other Minecraft profile controls continue to reject unsupported capabilities explicitly.
This guide describes the development sources; check the matching release documentation before selecting a published version.

## Mount and update

Call `mountWeb(definition, root, viewport)` on the browser agent and retain the returned host for the screen's lifetime.
The host exclusively owns the root's children until close.
Update caller-owned state or publish source revisions; the next animation frame commits them through the shared retained engine.
Call `host.render(newViewport)` from the browser resize integration when logical dimensions change.
Keep the same host, keys, editor state, scroll state and navigation state throughout updates and resizing.

The native editing element remains attached across matching keyed reevaluations.
Selection, native composition and focus therefore belong to that element rather than a recreated input overlay.
Composition updates notify common preedit modifiers; confirmed edits update canonical text state and notify committed-character modifiers.
Native deletion, replacement, clipboard and selection behavior stay with the browser.
Application key modifiers may consume shortcuts before native editing.
Tab and Shift+Tab use shared focus traversal, including its visibility and virtualization rules.

Composition with `Modifier.semantics` preserves standard roles, accessible names and disabled, selected or checked state in DOM, including controls drawn from ordinary layout, image and text primitives.
Outer semantic modifiers override matching fields supplied by the native component.
The host retains each logical operation surface across keyed updates, synchronizes native focus with the common owner and places the surface in paint order so an overlapping editor remains usable.
Transparent operation surfaces leave focus visuals to the common definition's painters.
Button surfaces use native buttons following the [browser button accessibility contract](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/button_role); pointer, keyboard and accessibility activation route through common input behavior.

Pointer coordinates account for CSS scaling of the root.
Pointer capture preserves an active drag outside its initial bounds; cancellation, window blur and close reset held input.
Wheel input is normalized before shared scroll or map navigation.
Touch taps use pointer events and native editors request the browser's own text input.
Navigation uses the common PointerGesture protocol: one-finger swipes pan maps or scroll lists, and two-finger pinches magnify maps.
The host waits until release before activating a touch tap, so swiping an actionable row does not click it.
Native editors keep their own touch selection and scrolling behavior.
OS candidate windows, mobile keyboards and physical touch devices require manual acceptance in addition to automated browser events.

Screen definitions may retain their Minecraft input policy, including Movement.
The browser preserves that policy as session metadata and has no gameplay forwarding target.
HUD presentation and game interaction modes remain unsupported.

## Prepare resources

`WebUiResources` is a caller-owned immutable snapshot of decoded images, explicitly loaded fonts and an optional skin-source resolver.
Prepare it before mounting, and pass it to the resource-aware `mountWeb` overload.
No network request or decoding runs from a component evaluation.
Missing resource identifiers fail explicitly.

Use `loadWebImage(url)` to decode browser-accessible images to immutable DrawImage pixels.
The loader uses anonymous CORS and a pixel budget, rejects unreadable images and releases transient decoding elements on success, failure or coroutine cancellation.
Same-origin authenticated requests follow browser cookie policy.
Cross-origin responses must permit the application's origin, and the deployment's content security policy must allow those resources.

Use `WebFont.load(family, url)` to prepare a browser font before creating a resource snapshot.
Register it under the ResourceId used by `UiText.WithFont` or an editor's `font` argument; `defaultFont` supplies the application's ordinary font.
The same prepared face supplies measurement and native drawing.
Use `WebFont.measure` for application-owned text layout that must use that prepared face.
Supply complete glyph coverage for every language the application accepts; selecting a family without those glyphs permits the browser's ordinary fallback and does not establish resource-font parity.
Native theme defaults are browser fonts, so a resource-font application must explicitly supply its default font.

The loader accepts browser-supported TTF, OTF and WOFF resources.
It does not parse Minecraft font JSON, bitmap provider atlases or Unihex archives.
Applications using those pack formats must prepare a licensed browser font from the intended resource-font data as part of their asset pipeline, and independently verify metrics and glyph coverage.
Mapping an unrelated system font to a missing pack family is not a verified conversion.
Browser-native IME candidate decoration is owned by the browser and operating system; custom editor frames and caret color do not control the candidate window.

Images remain immutable borrowed snapshots.
The caller owns prepared font handles and must close them after all borrowing hosts close.
Host close cancels animation, removes native listeners, releases state and tile observations, removes owned DOM and releases references to the resource snapshot.
A new resource-pack generation requires a new prepared snapshot and host; ordinary asynchronous data revisions retain the current host.

## Resolve player skins

Direct PlayerSkinSource.Pixels use a normalized 64 by 64 skin.
The renderer paints the face before the optional hat.
For current-player, name or UUID lookups, provide `WebUiResources.skins`, returning a stable StateSource of WebPlayerSkin for each active lookup.
Publish Loading, Ready or Failed from the host's network integration.
The shared Observe implementation commits updates at its frame cutoff and closes subscriptions when the head is removed or the host closes.
The resolver owns requests, retries, authentication, legacy-skin normalization, source generations and any external cache.
No browser request is implicitly sent to a third-party avatar service.

Use an application server or another explicitly selected service that provides CORS-compatible skin data.
Keep Minecraft session credentials on the server.
Only expose resources that the application's authorization policy allows that browser to fetch.

## Assets and verification

Strata does not grant redistribution rights for Minecraft's assets, another resource pack, user skins or proprietary application art.
Host only assets whose license and owner permit the intended public distribution.
Check the [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) and each resource pack or font license before publishing an asset bundle.
The interaction acceptance fixture uses original Strata test pixels and glyphs; it does not bundle game assets.

The compiled shared fixture is `integration:web`'s InteractionScenario.
`:integration:web:verifyWeb` drives Chromium, Firefox and WebKit against the built application.
It exercises native editing, synthetic composition during independent updates, focus traversal, scrolling, a large virtual list, map input, resource-font loading, resize identity and terminal release.
Desktop and touch-emulated contexts are recorded separately.
Receipts and screenshots are recreated under `integration/web/build/parity`, with the source revision, bundle hash and browser versions.
Synthetic composition does not prove OS candidate continuity, and touch emulation does not prove physical-device operation.
Record those manual results separately before claiming device or OS-IME acceptance.

For deterministic initial documents and static bundling, see [build and verification](../development/build.md#initial-web-documents).
For navigation and source ownership, see [tiled images](tiled-images.md) and [screens and state](screens-and-state.md).
