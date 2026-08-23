package dev.s7a.strata.integration.docs

/**
 * Produces deterministic Markdown sections for verified complete-screen use cases.
 */
internal object ShowcaseScreenMarkdown {
    /**
     * Builds one complete-screen section containing its verified image, compiled source, evidence class, and primitive-boundary guidance.
     *
     * @param spec typed screen catalog metadata.
     * @param source extracted compiled-example source.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun section(
        spec: ScreenScenario,
        source: String,
    ): String =
        """<a id="screen-${spec.screen.slug}"></a>

## ${spec.screen.title}

${summary(spec.screen)}

${evidence(spec.screen)}

![${spec.screen.title} screen showcase](components/screen-${spec.screen.slug}.png)

### Compiled screen

```kotlin
$source
```

### Primitive boundary

${primitiveBoundary(spec.screen)}
""".replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"

    private fun summary(screen: DocumentedScreen): String =
        when (screen) {
            DocumentedScreen.SocialInteractions -> "A Social Interactions reconstruction composes `Text`, `TextField`, `ScrollArea`, `Scrollbar`, `PlayerHead`, and ordinary layout primitives without introducing a purpose-specific SocialEntry component."
            DocumentedScreen.SynchronizedInventory -> "A loaded multiplayer container screen binds its lower grid to the real player inventory and can bind the upper grid to chest, ender-chest, furnace, or custom server-menu storage."
            DocumentedScreen.IndustrialController -> "A resource-pack-aware Mod controller composes a public custom image, Minecraft text, buttons, and layout primitives into an energy-machine interface."
            DocumentedScreen.PowerMilestones -> "An advancement-inspired Mod progression screen composes active vanilla advancement assets with an application-owned downstream graph component."
        }

    private fun evidence(screen: DocumentedScreen): String =
        when (screen.verification) {
            DocumentedScreen.Verification.NativeFabricHeadless -> "A loaded Fabric GameTest requires exact ARGB equality between the native Minecraft screen, the Strata Fabric screen, and the headless frame before this image is accepted."
            DocumentedScreen.Verification.LoadedServerFabric -> "A loaded Fabric client/server GameTest performs authoritative inventory interaction and records the resulting real Fabric screen; this bound screen intentionally has no portable-only headless substitute."
            DocumentedScreen.Verification.FabricHeadless -> "A loaded Fabric GameTest requires exact ARGB equality between the Strata Fabric screen and the headless frame while resolving assets from Minecraft's active resource manager."
        }

    private fun primitiveBoundary(screen: DocumentedScreen): String =
        when (screen) {
            DocumentedScreen.SocialInteractions -> "The player row remains example-owned because its mute/report relationship is Social-screen domain state. `PlayerHead`, scrolling, text input, text, buttons, and pointer actions remain reusable standard primitives."
            DocumentedScreen.SynchronizedInventory -> "`Slot` and `SlotBinding` are reusable primitives. The chest-shaped grouping and server menu decide which player, container, ender-chest, furnace, or custom inventory indices each slot binds."
            DocumentedScreen.IndustrialController -> "The runtime supplies general image, background, text, button, slot, and input primitives. Energy capacity, charge state, machine recipes, and networking remain application-owned state and server protocol."
            DocumentedScreen.PowerMilestones -> "`ExampleProgressGraph` deliberately stays in downstream example code because milestone names and graph meaning are specific to this Mod. Images, backgrounds, text, buttons, layout, and pointer actions remain reusable primitives."
        }
}
