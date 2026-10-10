package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteNode
import dev.s7a.strata.runtime.remote.RemoteTextCodec
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument

/**
 * Frozen public-message inputs for owned Text decoding and unchanged public-codec controls.
 * Text field and byte totals describe the independent wire schema, not observed arrays or allocation.
 */
@OptIn(InternalStrataRuntimeApi::class)
public enum class RemoteTextCorpus(
    public val textFields: Int,
    public val textBytes: Int,
) {
    NoTextAck(0, 0),
    EmptyText(3, 20),
    Ascii32(3, 52),
    AsciiMiB(3, 1_048_596),
    Latin132(3, 84),
    Cjk32(3, 116),
    Supplementary32(3, 148),
    Mixed128(3, 1812),
    ManyAscii256(258, 8212),
    ManyUnicode256(258, 13332),
    NestedMixed16(50, 356),
    PortableTranslatedFont(8, 72),
    SnapshotAscii100(301, 5209),
    SnapshotAscii8192(24577, 425993),
    SnapshotUnicode8192(24577, 589833),
    NearMessageLimit(3, 8_388_538),
    ;

    /**
     * Creates the complete immutable target before sampling; every identity and revision is one.
     */
    public fun message(): RemoteMessage =
        when (this) {
            NoTextAck -> RemoteMessage.Acknowledgement(1, 1, 1)
            SnapshotAscii100 -> snapshot(100, "A".repeat(32))
            SnapshotAscii8192 -> snapshot(8192, "A".repeat(32))
            SnapshotUnicode8192 -> snapshot(8192, "日本語🎮".repeat(4))
            else -> RemoteMessage.Action(1, 1, 1, ProjectionType(ResourceId("strata_benchmark", "text")), payload())
        }

    private fun payload(): ProjectionValue =
        when (this) {
            EmptyText -> {
                ProjectionValue.Text("")
            }

            Ascii32 -> {
                ProjectionValue.Text("A".repeat(32))
            }

            AsciiMiB -> {
                ProjectionValue.Text("A".repeat(1_048_576))
            }

            Latin132 -> {
                ProjectionValue.Text("é".repeat(32))
            }

            Cjk32 -> {
                ProjectionValue.Text("日".repeat(32))
            }

            Supplementary32 -> {
                ProjectionValue.Text("🎮".repeat(32))
            }

            Mixed128 -> {
                ProjectionValue.Text("A日本語🎮".repeat(128))
            }

            ManyAscii256 -> {
                ProjectionValue.Sequence(List(256) { ProjectionValue.Text("A".repeat(32)) })
            }

            ManyUnicode256 -> {
                ProjectionValue.Sequence(List(256) { ProjectionValue.Text("日本語🎮".repeat(4)) })
            }

            NestedMixed16 -> {
                ProjectionValue.Sequence(
                    List(16) { index ->
                        ProjectionValue.Sequence(
                            listOf(
                                ProjectionValue.Text("label"),
                                ProjectionValue.Text("日本語🎮"),
                                ProjectionValue.Bytes(ByteArray(32) { ((index + it) % 256).toByte() }),
                                ProjectionValue.Sequence(listOf(ProjectionValue.Flag(true), ProjectionValue.Integer(index.toLong()), ProjectionValue.Text("Aé"))),
                            ),
                        )
                    },
                )
            }

            PortableTranslatedFont -> {
                RemoteTextCodec.encode(
                    UiText.WithFont(
                        UiText.concat(
                            UiText.Literal("Hello "),
                            UiText.Translated("example.count", listOf(UiTextArgument.IntValue(4), UiTextArgument.Text(UiText.Literal("日本語 🎮"))), "Count %s"),
                        ),
                        ResourceId("example", "font"),
                    ),
                )
            }

            NearMessageLimit -> {
                ProjectionValue.Text("A".repeat(8_388_518))
            }

            NoTextAck, SnapshotAscii100, SnapshotAscii8192, SnapshotUnicode8192 -> {
                error("This corpus has no action payload.")
            }
        }

    private fun snapshot(
        count: Int,
        label: String,
    ): RemoteMessage.Snapshot {
        val type = ProjectionType(ResourceId("strata_benchmark", "node"))
        val value = RemoteTextCodec.encode(UiText.Literal(label))
        val tree =
            RemoteTree(
                1,
                (1..count).map { index ->
                    RemoteNode(RemoteDeclaration(index.toLong(), type, value), children = if (index == 1) (2..count).map(Int::toLong) else emptyList())
                },
            )
        return RemoteMessage.Snapshot(1, 1, RemoteTextCodec.encode(UiText.Literal("Benchmark")), tree)
    }
}
