package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteFraming
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemotePacket
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.ByteBuffer

/**
 * Sixty-four public operations on one common frozen corpus, including unchanged public-codec controls.
 * Framing, typed validation and immutable results remain inside receive timing; payload preparation stays outside.
 */
public open class RemoteTextBenchmark {
    /**
     * Encodes the complete prepared message with the public snapshot contract.
     */
    @Benchmark
    public fun publicMessageEncode(state: Input): ByteArray = state.codec.encode(state.message)

    /**
     * Decodes independent reference bytes with the unchanged public snapshot contract.
     */
    @Benchmark
    public fun publicMessageDecode(state: Input): RemoteMessage = state.codec.decode(state.wire)

    /**
     * Receives every prepared fragment through the actual already-negotiated connection.
     */
    @Benchmark
    public fun receiveMessage(state: Input): RemoteMessage = state.receive(state.connection, state.frames)

    /**
     * Includes construction, local greeting consumption, negotiation, complete receive and terminal close.
     */
    @Benchmark
    public fun receiveLifecycle(state: Input): RemoteMessage =
        state.newConnection().use { connection ->
            state.negotiate(connection)
            state.receive(connection, state.lifecycleFrames)
        }

    /**
     * Invocation-owned prepared wire and fragment headers for one JMH worker.
     * Only transport identities change before sampling; no runtime-specific corpus or private bridge is used.
     */
    @State(Scope.Thread)
    public open class Input {
        /**
         * All sixteen fixed inputs are expanded by generated JMH metadata.
         */
        @JvmField
        @Param
        public var corpus: RemoteTextCorpus = RemoteTextCorpus.NoTextAck

        /**
         * Identical negotiated and bootstrap bounds, with the native envelope excluded.
         */
        public val limits: RemoteLimits = RemotePacket.limits

        /**
         * Public codec control; it does not expose the connection's owned-input policy.
         */
        public val codec: RemoteMessageCodec = RemoteMessageCodec(limits)

        /**
         * Complete immutable expected result.
         */
        public lateinit var message: RemoteMessage

        /**
         * JDK reference logical message, prepared once.
         */
        public lateinit var wire: ByteArray

        /**
         * Prepared inner fragments, whose identity headers are refreshed outside measured work.
         */
        public lateinit var frames: List<ByteArray>

        /**
         * Independent fixed target identity two for a fresh lifecycle connection.
         */
        public lateinit var lifecycleFrames: List<ByteArray>

        /**
         * Actual persistent receive owner, closed at trial completion.
         */
        public lateinit var connection: RemoteConnection

        private lateinit var helloFrames: List<ByteArray>
        private lateinit var headers: List<ByteBuffer>
        private lateinit var types: Set<ProjectionType>
        private var nextIdentity = 2L
        private var sentBytes = 0L

        /**
         * Builds and checks reference bytes and complete target values before sampling.
         * The nonretaining outgoing sink consumes every local Hello frame's length.
         */
        @Setup(Level.Trial)
        public fun setup() {
            message = corpus.message()
            wire = RemoteTextReference.encode(message)
            check(codec.encode(message).contentEquals(wire))
            check(codec.encode(codec.decode(wire)).contentEquals(wire))
            check(RemoteTextReference.textTotals(message) == (corpus.textFields to corpus.textBytes))
            if (corpus == RemoteTextCorpus.NearMessageLimit) check(wire.size == limits.messageBytes)
            types =
                if (corpus == RemoteTextCorpus.NoTextAck) {
                    emptySet()
                } else {
                    setOf(
                        when (val target = message) {
                            is RemoteMessage.Action -> {
                                target.type
                            }

                            is RemoteMessage.Snapshot -> {
                                target.tree.nodes
                                    .getValue(target.tree.root)
                                    .declaration.type
                            }

                            else -> {
                                error("Text corpus requires a declared schema.")
                            }
                        },
                    )
                }
            val hello = RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, types)
            check(RemoteTextReference.textTotals(hello) == (if (types.isEmpty()) 0 to 0 else 2 to 20))
            helloFrames = fragments(RemoteTextReference.encode(hello), 1)
            frames = fragments(wire, 2)
            lifecycleFrames = fragments(wire, 2)
            headers = frames.map(ByteBuffer::wrap)
            nextIdentity = 2
            connection = newConnection()
            negotiate(connection)
        }

        /**
         * Advances transport identity without reconstructing payloads or resetting a live connection.
         * The shared CPU collector uses this same hook before each sample.
         */
        @Setup(Level.Invocation)
        public fun prepareReceive() {
            check(nextIdentity < Long.MAX_VALUE) { "Frozen receive identity space is exhausted." }
            val identity = nextIdentity++
            headers.forEach { it.putLong(0, identity) }
        }

        /**
         * Constructs a real owner with a consuming, nonretaining transport sink.
         */
        public fun newConnection(): RemoteConnection = RemoteConnection(types, limits) { frame -> sentBytes += frame.size }

        /**
         * Starts and drains the local Hello, then consumes the prepared peer greeting completely.
         */
        public fun negotiate(target: RemoteConnection) {
            target.start()
            target.flush(helloFrames.size)
            helloFrames.forEach { check(target.receive(it, 0) == null) }
            check(target.capabilities != null)
        }

        /**
         * Returns exactly one complete typed message after every fragment has been accepted.
         */
        public fun receive(
            target: RemoteConnection,
            fragments: List<ByteArray>,
        ): RemoteMessage {
            var complete: RemoteMessage? = null
            fragments.forEach { fragment ->
                val result = target.receive(fragment, 0)
                if (result != null) {
                    check(complete == null)
                    complete = result
                }
            }
            return checkNotNull(complete)
        }

        /**
         * Releases the current connection even when a trial fails.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (::connection.isInitialized) connection.close()
        }

        private fun fragments(
            bytes: ByteArray,
            identity: Long,
        ): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            RemoteFraming(limits).use { sender -> sender.send(bytes, result::add) }
            result.forEach { ByteBuffer.wrap(it).putLong(0, identity) }
            return result.toList()
        }
    }

    /**
     * Shared collector admission checks run outside every timing interval.
     */
    public companion object {
        /**
         * Admits every generated case in both modes and compares all four operations with independent wire bytes.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RemoteTextBenchmark::class.java), setOf("avgt", "sample")).size == 128)
            check(JmhWorkloadInventory.capture(listOf(RemoteProtocolBenchmark::class.java, RemoteTextBenchmark::class.java), setOf("avgt", "sample")).size == 188)
            val benchmark = RemoteTextBenchmark()
            RemoteTextCorpus.entries.forEach { corpus ->
                val input = Input().also { it.corpus = corpus }
                try {
                    input.setup()
                    check(benchmark.publicMessageEncode(input).contentEquals(input.wire))
                    check(RemoteTextReference.encode(benchmark.publicMessageDecode(input)).contentEquals(input.wire))
                    repeat(2) {
                        input.prepareReceive()
                        check(RemoteTextReference.encode(benchmark.receiveMessage(input)).contentEquals(input.wire))
                    }
                    check(RemoteTextReference.encode(benchmark.receiveLifecycle(input)).contentEquals(input.wire))
                } finally {
                    input.close()
                }
            }
        }
    }
}
