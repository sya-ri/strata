package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Untimed acceptance for detached glyph lifetime, changing destinations and deterministic complete text outputs.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object PortableTextWorkEvidence {
    /**
     * Requires the independent twelve-phase corpus and verifies every prepared source/density combination.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        check(JmhWorkloadInventory.capture(listOf(PortableTextBenchmark::class.java), setOf("avgt")).size == 12)
        PortableTextBenchmark.Face.entries.forEach { face ->
            listOf(1, 4).forEach { density ->
                val state =
                    PortableTextBenchmark.TextPixels().apply {
                        this.face = face
                        this.density = density
                    }
                state.setup()
                val first = state.paint()
                val second = state.paint()
                check(first.size.width == 1920 && first.size.height == 1080)
                check(first.copyArgb().contentEquals(second.copyArgb()).not())
                check(first.encodePng().contentEquals(state.paint().encodePng()))
                check(state.extract().drawCommands.isNotEmpty())
            }
        }
    }
}
