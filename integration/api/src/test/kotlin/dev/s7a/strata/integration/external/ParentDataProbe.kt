package dev.s7a.strata.integration.external

import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import java.util.concurrent.CountDownLatch

/**
 * Test-owned observations for external parent-data providers and consumers.
 *
 * @property captureScopes whether consumer callbacks retain their callback-lifetime scopes for validation.
 * @property blockMeasure whether the measurement callback waits for an explicit test release.
 * @property blockLayout whether the layout callback waits for an explicit test release.
 * @property queryInvalidIndex whether the consumer records rejected out-of-range queries before valid queries.
 */
public class ParentDataProbe public constructor(
    public val captureScopes: Boolean = false,
    public val blockMeasure: Boolean = false,
    public val blockLayout: Boolean = false,
    public val queryInvalidIndex: Boolean = false,
) {
    /**
     * Values read by modifier measurement callbacks.
     */
    internal val modifierMeasureValues: MutableList<ParentDataValue?> = ArrayList()

    /**
     * Values read by modifier layout callbacks.
     */
    internal val modifierLayoutValues: MutableList<ParentDataValue?> = ArrayList()

    /**
     * Values read by the external parent-data consumer during measurement.
     */
    internal val consumerMeasureValues: MutableList<ParentDataValue?> = ArrayList()

    /**
     * Values read by the external parent-data consumer during layout.
     */
    internal val consumerLayoutValues: MutableList<ParentDataValue?> = ArrayList()

    /**
     * Number of parent-data consumer measurement callbacks.
     */
    internal var consumerMeasureCalls: Int = 0

    /**
     * Number of parent-data consumer layout callbacks.
     */
    internal var consumerLayoutCalls: Int = 0

    /**
     * Number of child measurements requested by the parent-data consumer.
     */
    internal var consumerMeasureChildCalls: Int = 0

    /**
     * Number of child placements requested by the parent-data consumer.
     */
    internal var consumerPlaceChildCalls: Int = 0

    /**
     * Number of parent-data consumer paint callbacks.
     */
    internal var consumerPaintCalls: Int = 0

    /**
     * Number of parent-data consumer semantics callbacks.
     */
    internal var consumerSemanticsCalls: Int = 0

    /**
     * Number of parent-data reads attempted by an adversarial component node.
     */
    internal var componentProviderReads: Int = 0

    /**
     * Ordered parent-data lookup and component callback observations.
     */
    internal val events: MutableList<ParentDataEvent> = ArrayList()

    /**
     * Captured measurement scope, when requested by the fixture.
     */
    internal var measureScope: MeasureScope? = null

    /**
     * Captured layout scope, when requested by the fixture.
     */
    internal var layoutScope: LayoutScope? = null

    /**
     * Failure returned by an out-of-range measurement lookup.
     */
    internal var measureInvalidIndexFailure: Throwable? = null

    /**
     * Failure returned by an out-of-range layout lookup.
     */
    internal var layoutInvalidIndexFailure: Throwable? = null

    /**
     * Whether a consumer or modifier is currently performing a layout parent-data query.
     */
    internal var layoutParentDataQuery: Boolean = false

    /**
     * Signals that the measurement callback has captured its active scope.
     */
    internal val measureEntered: CountDownLatch = CountDownLatch(1)

    /**
     * Releases a blocked measurement callback.
     */
    internal val measureRelease: CountDownLatch = CountDownLatch(1)

    /**
     * Signals that the layout callback has captured its active scope.
     */
    internal val layoutEntered: CountDownLatch = CountDownLatch(1)

    /**
     * Releases a blocked layout callback.
     */
    internal val layoutRelease: CountDownLatch = CountDownLatch(1)

    /**
     * Provider nodes created by the external runtime.
     */
    internal val providers: MutableList<ParentDataProviderNode> = ArrayList()

    /**
     * Malicious provider nodes created by the external runtime.
     */
    internal val wrongProviders: MutableList<WrongParentDataProviderNode> = ArrayList()

    /**
     * Exact provider failure used by failure propagation tests.
     */
    internal val providerFailure: IllegalStateException = IllegalStateException("parent-data provider failure")
}
