package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects

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
        EncodingOutput(bytes).use { output -> write(output, value, 0, budget) }
        budget.checkTime()
        return bytes.toByteArray()
    }

    /**
     * Decodes exactly one value; truncated data, trailing bytes, and unknown tags are rejected.
     */
    public fun decode(bytes: ByteArray): ProjectionValue = decode(bytes, TextInput.Snapshot)

    /**
     * Decodes the connection's completed private assembly without temporary Text payload snapshots.
     * The connection must exclusively own the array and keep it unchanged until this call returns.
     * All returned values are detached, and neither the codec nor its results retain the input.
     */
    internal fun decodeOwned(bytes: ByteArray): ProjectionValue = decode(bytes, TextInput.Owned)

    private fun decode(
        bytes: ByteArray,
        textInput: TextInput,
    ): ProjectionValue {
        require(bytes.size <= limits.messageBytes) { "Remote message exceeds its byte limit." }
        return try {
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val budget = RemoteWorkBudget(limits)
            val value = read(input, 0, budget, textInput)
            budget.checkTime()
            require(input.hasRemaining().not()) { "Trailing bytes in a remote value." }
            value
        } catch (failure: IOException) {
            throw IllegalArgumentException("Malformed remote value.", failure)
        } catch (failure: BufferUnderflowException) {
            throw IllegalArgumentException("Malformed remote value.", failure)
        }
    }

    private fun write(
        output: EncodingOutput,
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
                if (value.value.all { it.code < 128 }) {
                    output.writeAscii(value.value)
                } else {
                    writeBytes(output, value.value.encodeToByteArray(throwOnInvalidSequence = true))
                }
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
        input: ByteBuffer,
        depth: Int,
        budget: RemoteWorkBudget,
        textInput: TextInput,
    ): ProjectionValue {
        budget.visit()
        require(depth < limits.valueDepth) { "Remote value nesting exceeds its limit." }
        val code = input.get().toInt() and 255
        return when (requireNotNull(Tag.entries.find { it.code == code }) { "Unknown remote value tag." }) {
            Tag.Absent -> {
                ProjectionValue.Absent
            }

            Tag.Flag -> {
                val flag = input.get().toInt() and 255
                require(flag <= 1) { "Invalid remote boolean." }
                ProjectionValue.Flag(flag == 1)
            }

            Tag.Integer -> {
                ProjectionValue.Integer(input.long)
            }

            Tag.Real -> {
                ProjectionValue.Real(input.double)
            }

            Tag.Text -> {
                ProjectionValue.Text(readText(input, textInput))
            }

            Tag.Bytes -> {
                ProjectionValue.Bytes(readBytes(input))
            }

            Tag.Sequence -> {
                val size = input.int
                require(size in 0..minOf(limits.collectionEntries, input.remaining())) { "Invalid remote collection length." }
                ProjectionValue.Sequence(List(size) { read(input, depth + 1, budget, textInput) })
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

    private fun readByteLength(input: ByteBuffer): Int {
        val size = input.int
        require(size in 0..minOf(limits.messageBytes, input.remaining())) { "Invalid remote byte length." }
        return size
    }

    private fun readBytes(input: ByteBuffer): ByteArray {
        val size = readByteLength(input)
        return ByteArray(size).also { input.get(it) }
    }

    private fun readText(
        input: ByteBuffer,
        textInput: TextInput,
    ): String =
        when (textInput) {
            TextInput.Snapshot -> {
                val bytes = readBytes(input)
                if (bytes.all { 0 <= it }) String(bytes, Charsets.UTF_8) else bytes.decodeToString(throwOnInvalidSequence = true)
            }

            TextInput.Owned -> {
                val size = readByteLength(input)
                val start = input.position()
                val end = start + size
                // Match the snapshot path's cursor advance, including malformed UTF-8 failures.
                input.position(end)
                val bytes = input.array()
                if ((start until end).all { 0 <= bytes[it] }) String(bytes, start, size, Charsets.UTF_8) else bytes.decodeToString(start, end, throwOnInvalidSequence = true)
            }
        }

    /**
     * Invocation-local ownership boundary; public callers always capture each Text field first.
     */
    private enum class TextInput {
        Snapshot,
        Owned,
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
     * Uses ordinary JDK primitives while packing admitted ASCII into the same bounded output buffer.
     * A text payload reserves once, creates no temporary encoder buffer, and preserves the inherited byte count.
     */
    private class EncodingOutput(
        private val bytes: BoundedOutput,
    ) : DataOutputStream(bytes) {
        fun writeAscii(value: String) {
            writeInt(value.length)
            bytes.appendAscii(value)
            // The bounded buffer proves the complete stream count remains within its positive Int limit.
            written = Math.addExact(written, value.length)
        }
    }

    /**
     * Checks growth before allocating beyond the message budget and writes only to invocation-owned storage.
     * The encoder never shares this buffer, so per-byte stream synchronization is unnecessary.
     */
    private class BoundedOutput(
        private val limit: Int,
    ) : ByteArrayOutputStream() {
        /**
         * Packs only caller-admitted ASCII after checking complete growth, without per-byte stream dispatch.
         * This buffer belongs exclusively to one encode invocation and is copied before being returned.
         */
        fun appendAscii(value: String) {
            require(value.length <= limit - count) { "Remote message exceeds its byte limit." }
            val end = count + value.length
            reserve(end)
            for (index in value.indices) buf[count + index] = value[index].code.toByte()
            count = end
        }

        override fun write(value: Int) {
            require(count < limit) { "Remote message exceeds its byte limit." }
            reserve(count + 1)
            buf[count] = value.toByte()
            count += 1
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            require(length <= limit - count) { "Remote message exceeds its byte limit." }
            Objects.checkFromIndexSize(offset, length, bytes.size)
            reserve(count + length)
            bytes.copyInto(buf, count, offset, offset + length)
            count += length
        }

        private fun reserve(required: Int) {
            if (buf.size < required) {
                val doubled = minOf(limit.toLong(), buf.size.toLong() * 2).toInt()
                buf = buf.copyOf(maxOf(required, doubled))
            }
        }
    }
}
