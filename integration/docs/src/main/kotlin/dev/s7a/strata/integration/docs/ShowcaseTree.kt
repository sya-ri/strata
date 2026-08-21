package dev.s7a.strata.integration.docs

/**
 * Immutable layout-component topology copied from one compiled Minecraft parity panel.
 *
 * Minecraft-specific text and pointer-button leaves are deliberately represented by the compiled source and pixels rather than this layout-only tree.
 */
internal class ShowcaseTree internal constructor(
    internal val component: DocumentedComponent,
    details: List<ShowcaseTreeDetail> = emptyList(),
    children: List<ShowcaseTree> = emptyList(),
) {
    internal val details: List<ShowcaseTreeDetail> = details.toList()
    internal val children: List<ShowcaseTree> = children.toList()
}
