package dev.s7a.strata.integration.docs

/**
 * Produces deterministic Markdown that distinguishes fresh CPU renders from explicit native inventory evidence.
 */
internal object ShowcaseScreenMarkdown {
    /**
     * Builds one complete-screen section containing its image, compiled source, generation provenance, independent evidence class, and primitive-boundary guidance.
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
            DocumentedScreen.Verification.NativeFabricHeadless -> "Documentation generation freshly renders this frame on the CPU from explicit Minecraft assets without starting the game or creating a GPU context. An independent loaded Fabric GameTest requires exact ARGB equality between the native Minecraft screen, the Strata Fabric screen, and the headless frame; its [native parity receipt](evidence/minecraft-26.2-parity.properties) is separate from the [headless generation receipt](components/headless-render.properties)."
            DocumentedScreen.Verification.LoadedServerFabric -> "This image is explicit native evidence from a loaded Fabric client/server GameTest that performs authoritative inventory interaction. Generation verifies its Minecraft version, PNG hash, and current compiled-source hash against the [native inventory receipt](evidence/minecraft-26.2-inventory.properties); it does not start a server or replace this bound screen with a portable-only substitute."
            DocumentedScreen.Verification.FabricHeadless -> "Documentation generation freshly renders this frame on the CPU from explicit Minecraft assets without starting the game or creating a GPU context. The independent loaded Fabric gate requires exact ARGB equality between the Strata Fabric screen and the headless frame using active resources; its [native parity receipt](evidence/minecraft-26.2-parity.properties) remains separate from the [headless generation receipt](components/headless-render.properties)."
        }

    private fun primitiveBoundary(screen: DocumentedScreen): String =
        when (screen) {
            DocumentedScreen.SocialInteractions -> "The player row remains example-owned because its mute/report relationship is Social-screen domain state. `PlayerHead`, scrolling, text input, text, buttons, and pointer actions remain reusable standard primitives."
            DocumentedScreen.SynchronizedInventory -> "`Slot` and `SlotBinding` are reusable primitives. The chest-shaped grouping and server menu decide which player, container, ender-chest, furnace, or custom inventory indices each slot binds."
            DocumentedScreen.IndustrialController -> "The runtime supplies general image, background, text, button, slot, and input primitives. Energy capacity, charge state, machine recipes, and networking remain application-owned state and server protocol."
            DocumentedScreen.PowerMilestones -> "`ExampleProgressGraph` deliberately stays in downstream example code because milestone names and graph meaning are specific to this Mod. Images, backgrounds, text, buttons, layout, and pointer actions remain reusable primitives."
        }
}
