package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.projection.ProjectionValue
import java.io.DataOutputStream

/**
 * Independent projected-value writer and Text counter for the frozen reference message schemas.
 * Message projection remains in RemoteTextReference; this stateless helper never invokes a production codec.
 */
internal object RemoteTextValueReference {
    /**
     * Counts Text leaves and their UTF-8 lengths without measuring runtime copies.
     */
    internal fun textTotals(value: ProjectionValue): Pair<Int, Int> =
        when (value) {
            is ProjectionValue.Text -> {
                1 to value.value.toByteArray(Charsets.UTF_8).size
            }

            is ProjectionValue.Sequence -> {
                value.values.fold(0 to 0) { total, child ->
                    val next = textTotals(child)
                    total.first + next.first to total.second + next.second
                }
            }

            else -> {
                0 to 0
            }
        }

    /**
     * Writes a projected value in version-one wire order without retaining or closing the caller-owned stream.
     */
    internal fun write(
        output: DataOutputStream,
        value: ProjectionValue,
    ) {
        when (value) {
            ProjectionValue.Absent -> {
                output.writeByte(0)
            }

            is ProjectionValue.Flag -> {
                output.writeByte(1)
                output.writeBoolean(value.value)
            }

            is ProjectionValue.Integer -> {
                output.writeByte(2)
                output.writeLong(value.value)
            }

            is ProjectionValue.Real -> {
                output.writeByte(3)
                output.writeDouble(value.value)
            }

            is ProjectionValue.Text -> {
                output.writeByte(4)
                writeBytes(output, value.value.toByteArray(Charsets.UTF_8))
            }

            is ProjectionValue.Bytes -> {
                output.writeByte(5)
                writeBytes(output, value.toByteArray())
            }

            is ProjectionValue.Sequence -> {
                output.writeByte(6)
                output.writeInt(value.values.size)
                value.values.forEach { write(output, it) }
            }
        }
    }

    private fun writeBytes(
        output: DataOutputStream,
        bytes: ByteArray,
    ) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }
}
