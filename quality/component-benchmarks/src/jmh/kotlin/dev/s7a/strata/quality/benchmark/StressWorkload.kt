package dev.s7a.strata.quality.benchmark

/**
 * Independent stress inputs; lengths and counts are explicit workload conditions, not invented API limits.
 */
public enum class StressWorkload {
    VirtualList100,
    VirtualList1000000,
    Canvas256,
    Canvas1024,
    TextField32,
    TextField16384,
    Checkbox,
    Animation,
    FanOut128,
    FanOut4096,
    NineSlice1,
    NineSlice2,
    NineSlice4,
}
