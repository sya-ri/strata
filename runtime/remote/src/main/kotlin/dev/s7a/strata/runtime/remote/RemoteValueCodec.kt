package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Strict bounded binary encoding for detached projection values.
 * The format uses explicit tags, big-endian numbers, and length-prefixed strict UTF-8.
 * Instances are immutable and do not retain encoded values or decoded buffers.
 */
public class RemoteValueCodec(
    private val limits: RemoteLimits = RemoteLimits(),
) {
    /**
     * Encodes one complete value, rejecting oversized output before growing its buffer.
     */
    public fun encode(value: ProjectionValue): ByteArray {
        val bytes = BoundedOutput(limits.messageBytes)
        val budget = RemoteWorkBudget(limits)
        DataOutputStream(bytes).use { output -> write(output, value, 0, budget) }
        budget.checkTime()
        return bytes.toByteArray()
    }

    /**
     * Decodes exactly one value; truncated data, trailing bytes, and unknown tags are rejected.
     */
    public fun decode(bytes: ByteArray): ProjectionValue {
        require(bytes.size <= limits.messageBytes) { "Remote message exceeds its byte limit." }
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val budget = RemoteWorkBudget(limits)
                val value = read(input, 0, budget)
                budget.checkTime()
                require(input.available() == 0) { "Trailing bytes in a remote value." }
                value
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("Malformed remote value.", failure)
        }
    }

    private fun write(
        output: DataOutputStream,
        value: ProjectionValue,
        depth: Int,
        budget: RemoteWorkBudget,
    ) {
        budget.visit()
        require(depth < limits.valueDepth) { "Remote value nesting exceeds its limit." }
        when (value) {
            ProjectionValue.Absent -> {
                output.writeByte(Tag.Absent.code)
            }

            is ProjectionValue.Flag -> {
                output.writeByte(Tag.Flag.code)
                output.writeBoolean(value.value)
            }

            is ProjectionValue.Integer -> {
                output.writeByte(Tag.Integer.code)
                output.writeLong(value.value)
            }

            is ProjectionValue.Real -> {
                output.writeByte(Tag.Real.code)
                output.writeDouble(value.value)
            }

            is ProjectionValue.Text -> {
                require(value.value.length <= limits.messageBytes) { "Remote text exceeds its limit." }
                output.writeByte(Tag.Text.code)
                writeBytes(output, value.value.encodeToByteArray(throwOnInvalidSequence = true))
            }

            is ProjectionValue.Bytes -> {
                require(value.size <= limits.messageBytes) { "Remote bytes exceed their limit." }
                output.writeByte(Tag.Bytes.code)
                writeBytes(output, value.toByteArray())
            }

            is ProjectionValue.Sequence -> {
                require(value.values.size <= limits.collectionEntries) { "Remote collection exceeds its limit." }
                output.writeByte(Tag.Sequence.code)
                output.writeInt(value.values.size)
                value.values.forEach { write(output, it, depth + 1, budget) }
            }
        }
    }

    private fun read(
        input: DataInputStream,
        depth: Int,
        budget: RemoteWorkBudget,
    ): ProjectionValue {
        budget.visit()
        require(depth < limits.valueDepth) { "Remote value nesting exceeds its limit." }
        val code = input.readUnsignedByte()
        return when (requireNotNull(Tag.entries.find { it.code == code }) { "Unknown remote value tag." }) {
            Tag.Absent -> {
                ProjectionValue.Absent
            }

            Tag.Flag -> {
                val flag = input.readUnsignedByte()
                require(flag <= 1) { "Invalid remote boolean." }
                ProjectionValue.Flag(flag == 1)
            }

            Tag.Integer -> {
                ProjectionValue.Integer(input.readLong())
            }

            Tag.Real -> {
                ProjectionValue.Real(input.readDouble())
            }

            Tag.Text -> {
                ProjectionValue.Text(readBytes(input).decodeToString(throwOnInvalidSequence = true))
            }

            Tag.Bytes -> {
                ProjectionValue.Bytes(readBytes(input))
            }

            Tag.Sequence -> {
                val size = input.readInt()
                require(size in 0..minOf(limits.collectionEntries, input.available())) { "Invalid remote collection length." }
                ProjectionValue.Sequence(List(size) { read(input, depth + 1, budget) })
            }
        }
    }

    private fun writeBytes(
        output: DataOutputStream,
        value: ByteArray,
    ) {
        require(value.size <= limits.messageBytes) { "Remote byte sequence exceeds its limit." }
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..minOf(limits.messageBytes, input.available())) { "Invalid remote byte length." }
        return ByteArray(size).also(input::readFully)
    }

    /**
     * Stable external format tags; new schemas must not renumber existing entries.
     */
    private enum class Tag(
        val code: Int,
    ) {
        Absent(0),
        Flag(1),
        Integer(2),
        Real(3),
        Text(4),
        Bytes(5),
        Sequence(6),
    }

    /**
     * Checks growth before ByteArrayOutputStream can allocate beyond the message budget.
     */
    private class BoundedOutput(
        private val limit: Int,
    ) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < limit) { "Remote message exceeds its byte limit." }
            reserve(count + 1)
            super.write(value)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            require(length <= limit - count) { "Remote message exceeds its byte limit." }
            reserve(count + length)
            super.write(bytes, offset, length)
        }

        private fun reserve(required: Int) {
            if (buf.size < required) {
                val doubled = minOf(limit.toLong(), buf.size.toLong() * 2).toInt()
                buf = buf.copyOf(maxOf(required, doubled))
            }
        }
    }
}
