package dev.s7a.strata.integration.docs

/**
 * Immutable component topology copied from one compiled showcase definition.
 *
 * The tree includes the featured component, any minimum parent layout used by its complete frame, and the children needed to demonstrate its responsibility.
 */
internal class ShowcaseTree internal constructor(
    internal val component: DocumentedComponent,
    details: List<ShowcaseTreeDetail> = emptyList(),
    children: List<ShowcaseTree> = emptyList(),
) {
    internal val details: List<ShowcaseTreeDetail> = details.toList()
    internal val children: List<ShowcaseTree> = children.toList()
}
