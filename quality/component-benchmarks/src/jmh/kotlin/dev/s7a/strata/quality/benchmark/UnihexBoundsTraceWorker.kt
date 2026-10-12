package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real packaged trace worker; ordinary rows delegate to the preserved single-row entry.
 * Independent qualification controls call the actual runtime bounds handle or a closed public engine.
 */
public object UnihexBoundsTraceWorker {
    private enum class Qualification { QSingle, QMultiple, QZero, QFailure }

    /**
     * Loads every required prepared class before an operation, preserving PID-qualified origin evidence.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2)
        val request = JsonParser.parseString(Files.readString(Path.of(args[0]))).asJsonObject
        for (name in request.getAsJsonObject("required_classes").keySet()) Class.forName(name, false, ClassLoader.getSystemClassLoader())
        // A zero-bounds control still needs its entry observer installed before the operation marker.
        Class.forName("dev.s7a.strata.runtime.minecraft.font.FontHexGlyph", true, ClassLoader.getSystemClassLoader())
        val qualification = Qualification.entries.firstOrNull { it.name == args[1] }
        if (qualification == null) {
            UnihexBoundsWorkEvidence.main(arrayOf(args[1]))
        } else {
            qualify(qualification)
        }
    }

    private fun qualify(control: Qualification) {
        val state =
            UnihexBoundsBenchmark.BoundsState().also {
                it.fixture = UnihexBoundsFixture.W8LeftEdge
                it.setup()
            }
        val closed =
            if (control == Qualification.QFailure) {
                UnihexBoundsBenchmark.GlyphState().also {
                    it.fixture = UnihexBoundsFixture.W8LeftEdge
                    it.setup()
                    it.close()
                }
            } else {
                null
            }
        println("Unihex operation begin ${control.name} independent actual runtime control")
        when (control) {
            Qualification.QSingle -> {
                check(state.bounds() == 0..0)
            }

            Qualification.QMultiple -> {
                repeat(3) { check(state.bounds() == 0..0) }
            }

            Qualification.QZero -> {}

            Qualification.QFailure -> {
                val failure = runCatching { checkNotNull(closed).glyph() }.exceptionOrNull()
                check(failure is IllegalStateException)
                println("Unihex operation failure ${control.name} ${failure.javaClass.name}")
            }
        }
        println("Unihex operation end ${control.name} independent control complete")
    }
}
