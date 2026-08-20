package dev.s7a.strata.integration.docs

/**
 * Finds the single logical node documented by a component page.
 *
 * @param component typed component identity to locate.
 * @return the unique matching logical node.
 * @throws IllegalArgumentException when the tree contains zero or multiple matches.
 */
internal fun ShowcaseTree.featured(component: DocumentedComponent): ShowcaseTree {
    val matches =
        buildList {
            fun visit(node: ShowcaseTree) {
                if (node.component == component) add(node)
                node.children.forEach(::visit)
            }
            visit(this@featured)
        }
    require(matches.size == 1) { "Showcase page must have exactly one featured ${component.apiMethodName} node." }
    return matches.single()
}

/**
 * Immutable logical component topology used to validate returned public element trees.
 */
internal class ShowcaseTree internal constructor(
    internal val component: DocumentedComponent,
    details: List<ShowcaseTreeDetail> = emptyList(),
    children: List<ShowcaseTree> = emptyList(),
) {
    internal val details: List<ShowcaseTreeDetail> = details.toList()
    internal val children: List<ShowcaseTree> = children.toList()
}
