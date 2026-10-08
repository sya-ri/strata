package dev.s7a.strata.quality.benchmark

/**
 * One-operation adapters shared by supplemental CPU and untimed index observation; no custom sampling lives here.
 */
internal enum class RemotePatchIndexOperation(val run: (RemotePatchIndexBenchmark.Scene) -> Any) {
    Apply(RemotePatchIndexBenchmark.Scene::applyPatch),
    ClientUpdate(RemotePatchIndexBenchmark.Scene::updateClient),
}
