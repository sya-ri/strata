package dev.s7a.strata.quality.benchmark

/**
 * Executable one-operation adapters shared by untimed copy observation and the supplemental shared CPU collector.
 * Each entry delegates to the exact corresponding JMH method's scene operation.
 */
internal enum class RemoteBytesOperation(val run: (RemoteBytesBenchmark.Scene) -> Any) {
    ProjectionConstruction(RemoteBytesBenchmark.Scene::constructProjection),
    ValueEncode(RemoteBytesBenchmark.Scene::encodeValue),
    ValueDecode(RemoteBytesBenchmark.Scene::decodeValue),
    ValueRoundTrip(RemoteBytesBenchmark.Scene::roundTripValue),
    ActionEncode(RemoteBytesBenchmark.Scene::encodeAction),
    ActionDecode(RemoteBytesBenchmark.Scene::decodeAction),
    ActionRoundTrip(RemoteBytesBenchmark.Scene::roundTripAction),
    ActionFraming(RemoteBytesBenchmark.Scene::frameAction),
}
