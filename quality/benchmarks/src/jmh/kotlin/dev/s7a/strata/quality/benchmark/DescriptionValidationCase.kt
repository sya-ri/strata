package dev.s7a.strata.quality.benchmark

/**
 * Frozen complete declaration corpus: two public frame routes, three shapes, four sizes and three key arrangements.
 * Four explicit controls follow the 72 data cases; descendants exclude the one emitted root.
 */
public enum class DescriptionValidationCase(
    public val route: Route,
    public val shape: Shape,
    public val descendants: Int,
    public val keys: Keys,
) {
    RootDefinitionWide1None(Route.RootDefinition, Shape.Wide, 1, Keys.None),
    RootDefinitionWide1All(Route.RootDefinition, Shape.Wide, 1, Keys.All),
    RootDefinitionWide1Alternate(Route.RootDefinition, Shape.Wide, 1, Keys.Alternate),
    RootDefinitionWide16None(Route.RootDefinition, Shape.Wide, 16, Keys.None),
    RootDefinitionWide16All(Route.RootDefinition, Shape.Wide, 16, Keys.All),
    RootDefinitionWide16Alternate(Route.RootDefinition, Shape.Wide, 16, Keys.Alternate),
    RootDefinitionWide128None(Route.RootDefinition, Shape.Wide, 128, Keys.None),
    RootDefinitionWide128All(Route.RootDefinition, Shape.Wide, 128, Keys.All),
    RootDefinitionWide128Alternate(Route.RootDefinition, Shape.Wide, 128, Keys.Alternate),
    RootDefinitionWide1024None(Route.RootDefinition, Shape.Wide, 1024, Keys.None),
    RootDefinitionWide1024All(Route.RootDefinition, Shape.Wide, 1024, Keys.All),
    RootDefinitionWide1024Alternate(Route.RootDefinition, Shape.Wide, 1024, Keys.Alternate),
    RootDefinitionChainGroups16Count1None(Route.RootDefinition, Shape.ChainGroups16, 1, Keys.None),
    RootDefinitionChainGroups16Count1All(Route.RootDefinition, Shape.ChainGroups16, 1, Keys.All),
    RootDefinitionChainGroups16Count1Alternate(Route.RootDefinition, Shape.ChainGroups16, 1, Keys.Alternate),
    RootDefinitionChainGroups16Count16None(Route.RootDefinition, Shape.ChainGroups16, 16, Keys.None),
    RootDefinitionChainGroups16Count16All(Route.RootDefinition, Shape.ChainGroups16, 16, Keys.All),
    RootDefinitionChainGroups16Count16Alternate(Route.RootDefinition, Shape.ChainGroups16, 16, Keys.Alternate),
    RootDefinitionChainGroups16Count128None(Route.RootDefinition, Shape.ChainGroups16, 128, Keys.None),
    RootDefinitionChainGroups16Count128All(Route.RootDefinition, Shape.ChainGroups16, 128, Keys.All),
    RootDefinitionChainGroups16Count128Alternate(Route.RootDefinition, Shape.ChainGroups16, 128, Keys.Alternate),
    RootDefinitionChainGroups16Count1024None(Route.RootDefinition, Shape.ChainGroups16, 1024, Keys.None),
    RootDefinitionChainGroups16Count1024All(Route.RootDefinition, Shape.ChainGroups16, 1024, Keys.All),
    RootDefinitionChainGroups16Count1024Alternate(Route.RootDefinition, Shape.ChainGroups16, 1024, Keys.Alternate),
    RootDefinitionBalancedBinary1None(Route.RootDefinition, Shape.BalancedBinary, 1, Keys.None),
    RootDefinitionBalancedBinary1All(Route.RootDefinition, Shape.BalancedBinary, 1, Keys.All),
    RootDefinitionBalancedBinary1Alternate(Route.RootDefinition, Shape.BalancedBinary, 1, Keys.Alternate),
    RootDefinitionBalancedBinary16None(Route.RootDefinition, Shape.BalancedBinary, 16, Keys.None),
    RootDefinitionBalancedBinary16All(Route.RootDefinition, Shape.BalancedBinary, 16, Keys.All),
    RootDefinitionBalancedBinary16Alternate(Route.RootDefinition, Shape.BalancedBinary, 16, Keys.Alternate),
    RootDefinitionBalancedBinary128None(Route.RootDefinition, Shape.BalancedBinary, 128, Keys.None),
    RootDefinitionBalancedBinary128All(Route.RootDefinition, Shape.BalancedBinary, 128, Keys.All),
    RootDefinitionBalancedBinary128Alternate(Route.RootDefinition, Shape.BalancedBinary, 128, Keys.Alternate),
    RootDefinitionBalancedBinary1024None(Route.RootDefinition, Shape.BalancedBinary, 1024, Keys.None),
    RootDefinitionBalancedBinary1024All(Route.RootDefinition, Shape.BalancedBinary, 1024, Keys.All),
    RootDefinitionBalancedBinary1024Alternate(Route.RootDefinition, Shape.BalancedBinary, 1024, Keys.Alternate),
    ObserveRebuildWide1None(Route.ObserveRebuild, Shape.Wide, 1, Keys.None),
    ObserveRebuildWide1All(Route.ObserveRebuild, Shape.Wide, 1, Keys.All),
    ObserveRebuildWide1Alternate(Route.ObserveRebuild, Shape.Wide, 1, Keys.Alternate),
    ObserveRebuildWide16None(Route.ObserveRebuild, Shape.Wide, 16, Keys.None),
    ObserveRebuildWide16All(Route.ObserveRebuild, Shape.Wide, 16, Keys.All),
    ObserveRebuildWide16Alternate(Route.ObserveRebuild, Shape.Wide, 16, Keys.Alternate),
    ObserveRebuildWide128None(Route.ObserveRebuild, Shape.Wide, 128, Keys.None),
    ObserveRebuildWide128All(Route.ObserveRebuild, Shape.Wide, 128, Keys.All),
    ObserveRebuildWide128Alternate(Route.ObserveRebuild, Shape.Wide, 128, Keys.Alternate),
    ObserveRebuildWide1024None(Route.ObserveRebuild, Shape.Wide, 1024, Keys.None),
    ObserveRebuildWide1024All(Route.ObserveRebuild, Shape.Wide, 1024, Keys.All),
    ObserveRebuildWide1024Alternate(Route.ObserveRebuild, Shape.Wide, 1024, Keys.Alternate),
    ObserveRebuildChainGroups16Count1None(Route.ObserveRebuild, Shape.ChainGroups16, 1, Keys.None),
    ObserveRebuildChainGroups16Count1All(Route.ObserveRebuild, Shape.ChainGroups16, 1, Keys.All),
    ObserveRebuildChainGroups16Count1Alternate(Route.ObserveRebuild, Shape.ChainGroups16, 1, Keys.Alternate),
    ObserveRebuildChainGroups16Count16None(Route.ObserveRebuild, Shape.ChainGroups16, 16, Keys.None),
    ObserveRebuildChainGroups16Count16All(Route.ObserveRebuild, Shape.ChainGroups16, 16, Keys.All),
    ObserveRebuildChainGroups16Count16Alternate(Route.ObserveRebuild, Shape.ChainGroups16, 16, Keys.Alternate),
    ObserveRebuildChainGroups16Count128None(Route.ObserveRebuild, Shape.ChainGroups16, 128, Keys.None),
    ObserveRebuildChainGroups16Count128All(Route.ObserveRebuild, Shape.ChainGroups16, 128, Keys.All),
    ObserveRebuildChainGroups16Count128Alternate(Route.ObserveRebuild, Shape.ChainGroups16, 128, Keys.Alternate),
    ObserveRebuildChainGroups16Count1024None(Route.ObserveRebuild, Shape.ChainGroups16, 1024, Keys.None),
    ObserveRebuildChainGroups16Count1024All(Route.ObserveRebuild, Shape.ChainGroups16, 1024, Keys.All),
    ObserveRebuildChainGroups16Count1024Alternate(Route.ObserveRebuild, Shape.ChainGroups16, 1024, Keys.Alternate),
    ObserveRebuildBalancedBinary1None(Route.ObserveRebuild, Shape.BalancedBinary, 1, Keys.None),
    ObserveRebuildBalancedBinary1All(Route.ObserveRebuild, Shape.BalancedBinary, 1, Keys.All),
    ObserveRebuildBalancedBinary1Alternate(Route.ObserveRebuild, Shape.BalancedBinary, 1, Keys.Alternate),
    ObserveRebuildBalancedBinary16None(Route.ObserveRebuild, Shape.BalancedBinary, 16, Keys.None),
    ObserveRebuildBalancedBinary16All(Route.ObserveRebuild, Shape.BalancedBinary, 16, Keys.All),
    ObserveRebuildBalancedBinary16Alternate(Route.ObserveRebuild, Shape.BalancedBinary, 16, Keys.Alternate),
    ObserveRebuildBalancedBinary128None(Route.ObserveRebuild, Shape.BalancedBinary, 128, Keys.None),
    ObserveRebuildBalancedBinary128All(Route.ObserveRebuild, Shape.BalancedBinary, 128, Keys.All),
    ObserveRebuildBalancedBinary128Alternate(Route.ObserveRebuild, Shape.BalancedBinary, 128, Keys.Alternate),
    ObserveRebuildBalancedBinary1024None(Route.ObserveRebuild, Shape.BalancedBinary, 1024, Keys.None),
    ObserveRebuildBalancedBinary1024All(Route.ObserveRebuild, Shape.BalancedBinary, 1024, Keys.All),
    ObserveRebuildBalancedBinary1024Alternate(Route.ObserveRebuild, Shape.BalancedBinary, 1024, Keys.Alternate),

    EmptyRootRebuild(Route.RootDefinition, Shape.Wide, 0, Keys.None),
    EmptyObservedRebuild(Route.ObserveRebuild, Shape.Wide, 0, Keys.None),
    SameDescriptionRootReevaluation(Route.SameDescription, Shape.Wide, 128, Keys.None),
    CleanSettledFrame(Route.Clean, Shape.Wide, 0, Keys.None),
    ;

    /**
     * Public phase whose assignment/publication and completed frame are inside timing.
     */
    public enum class Route {
        RootDefinition,
        ObserveRebuild,
        /**
         * Invalidates root content while returning an identical prepared description.
         */
        SameDescription,
        /**
         * Changes no state after settling.
         */
        Clean,
    }

    /**
     * Ordered membership constructed before the trial; chains have at most sixteen descendants.
     */
    public enum class Shape {
        Wide,
        ChainGroups16,
        /**
         * Breadth-first indices with at most two children per parent.
         */
        BalancedBinary,
    }

    /**
     * Stable distinct sibling keys; Alternate keys the second, fourth and subsequent even-positioned children.
     */
    public enum class Keys {
        None,
        All,
        Alternate,
    }
}
