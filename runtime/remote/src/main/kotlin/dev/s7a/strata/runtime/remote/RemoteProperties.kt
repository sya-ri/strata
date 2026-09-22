@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.TabSelectionIndicator
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import java.util.UUID

/**
 * Paired portable property schemas used by the profile-component runtime boundary.
 * Every composite decoder consumes its complete record before any native resource is resolved.
 */
@Suppress("TooManyFunctions") // Keeps the two directions of the finite standard property vocabulary together.
internal object RemoteProperties {
    private val images = RemoteImageCodec()

    /**
     * Captures a complete ordered property record.
     */
    fun record(vararg values: ProjectionValue): ProjectionValue = ProjectionValue.Sequence(values.toList())

    /**
     * Encodes an integer property.
     */
    fun encode(value: Int): ProjectionValue = ProjectionValue.Integer(value.toLong())

    /**
     * Encodes a Boolean property.
     */
    fun encode(value: Boolean): ProjectionValue = ProjectionValue.Flag(value)

    /**
     * Encodes a finite real property.
     */
    fun encode(value: Double): ProjectionValue = ProjectionValue.Real(value)

    /**
     * Encodes a closed enum at the standard wire-schema boundary.
     */
    fun encode(value: Enum<*>): ProjectionValue = encode(value.ordinal)

    /**
     * Encodes the sealed nine-slice mapping policy.
     */
    fun encode(value: NineSliceCenterMode): ProjectionValue = encode(value === NineSliceCenterMode.Stretched)

    /**
     * Encodes the indicator policy; custom content is transferred as an ordinary child declaration.
     */
    fun encode(value: TabSelectionIndicator): ProjectionValue = encode(value is TabSelectionIndicator.Custom)

    /**
     * Preserves unresolved text and font selection.
     */
    fun encode(value: UiText): ProjectionValue = RemoteTextCodec.encode(value)

    /**
     * Encodes a non-negative logical size.
     */
    fun encode(value: IntSize): ProjectionValue = record(encode(value.width), encode(value.height))

    /**
     * Encodes a source crop without resolving pixels.
     */
    fun encode(value: IntRect): ProjectionValue = record(encode(value.left), encode(value.top), encode(value.right), encode(value.bottom))

    /**
     * Encodes ordered logical insets.
     */
    fun encode(value: Insets): ProjectionValue = record(encode(value.left), encode(value.top), encode(value.right), encode(value.bottom))

    /**
     * Encodes a structural resource identifier.
     */
    fun encode(value: ResourceId): ProjectionValue = record(ProjectionValue.Text(value.namespace), ProjectionValue.Text(value.path))

    /**
     * Encodes structural resources or immutable pixels.
     */
    fun encode(value: ImageSource): ProjectionValue = images.encode(value)

    /**
     * Encodes the portable player-skin source without performing a lookup.
     */
    fun encode(value: PlayerSkinSource): ProjectionValue =
        when (value) {
            PlayerSkinSource.CurrentPlayer -> record(encode(SkinKind.Current))
            is PlayerSkinSource.Pixels -> record(encode(SkinKind.Pixels), encode(ImageSource.Pixels(value.skin)))
            is PlayerSkinSource.Name -> record(encode(SkinKind.Name), ProjectionValue.Text(value.value))
            is PlayerSkinSource.Uuid -> record(encode(SkinKind.Uuid), ProjectionValue.Text(value.value.toString()))
        }

    /**
     * Encodes a standard text layout, preserving the explicit single-line policy.
     */
    fun encode(value: TextLayout): ProjectionValue =
        when (value) {
            TextLayout.SingleLine -> ProjectionValue.Absent
            is TextLayout.Multiline -> record(encode(value.wrap), encode(value.maxLines), encode(value.overflow), encode(value.lineSpacing))
        }

    /**
     * Encodes a fixed or visible-row editor viewport.
     */
    fun encode(value: TextAreaViewport): ProjectionValue =
        when (value) {
            is TextAreaViewport.Lines -> record(encode(ViewportKind.Lines), encode(value.width), encode(value.lines))
            is TextAreaViewport.Size -> record(encode(ViewportKind.Size), encode(value.size))
        }

    /**
     * Encodes standard editor frames and independent caret/composition colors.
     */
    fun encode(value: TextInputAppearance): ProjectionValue =
        when (value) {
            TextInputAppearance.Default -> {
                ProjectionValue.Absent
            }

            is TextInputAppearance.Custom -> {
                record(
                    encode(value.normal),
                    encode(value.focused),
                    encode(value.disabled),
                    encode(value.border),
                    encode(value.caretColor.value),
                    encode(value.compositionUnderlineColor.value),
                )
            }
        }

    /**
     * Encodes an authoritative menu coordinate without transmitting inventory contents or client click decisions.
     */
    fun encode(value: SlotBinding): ProjectionValue = record(encode(value.source), encode(value.index))

    /**
     * Encodes an explicitly optional schema field.
     */
    fun <T : Any> optional(
        value: T?,
        encode: (T) -> ProjectionValue,
    ): ProjectionValue = value?.let(encode) ?: ProjectionValue.Absent

    /**
     * Decodes an explicitly optional schema field.
     */
    fun <T : Any> optional(
        value: ProjectionValue,
        decode: (ProjectionValue) -> T,
    ): T? = if (value === ProjectionValue.Absent) null else decode(value)

    /**
     * Decodes a closed enum with checked bounds.
     */
    inline fun <reified T : Enum<T>> enumeration(fields: ProjectionFields): T {
        val values = enumValues<T>()
        return values[fields.int(values.indices)]
    }

    /**
     * Decodes a logical size.
     */
    fun size(value: ProjectionValue): IntSize = read(value) { IntSize(int(0..Int.MAX_VALUE), int(0..Int.MAX_VALUE)) }

    /**
     * Decodes a source crop with non-negative extents.
     */
    fun rectangle(value: ProjectionValue): IntRect = read(value) { IntRect(int(), int(), int(), int()) }

    /**
     * Decodes logical insets.
     */
    fun insets(value: ProjectionValue): Insets = read(value) { Insets(int(0..Int.MAX_VALUE), int(0..Int.MAX_VALUE), int(0..Int.MAX_VALUE), int(0..Int.MAX_VALUE)) }

    /**
     * Validates a resource identifier before client resolution.
     */
    fun resource(value: ProjectionValue): ResourceId = read(value) { ResourceId(text(), text()) }

    /**
     * Decodes immutable pixels or an unresolved resource reference.
     */
    fun image(value: ProjectionValue): ImageSource = images.decode(value)

    /**
     * Decodes a skin lookup or immutable normalized pixels.
     */
    fun skin(value: ProjectionValue): PlayerSkinSource =
        read(value) {
            when (enumeration<SkinKind>(this)) {
                SkinKind.Current -> PlayerSkinSource.CurrentPlayer
                SkinKind.Pixels -> PlayerSkinSource.Pixels(requireNotNull(image(value()) as? ImageSource.Pixels).image)
                SkinKind.Name -> PlayerSkinSource.Name(text())
                SkinKind.Uuid -> PlayerSkinSource.Uuid(UUID.fromString(text()))
            }
        }

    /**
     * Decodes and validates standard text layout limits.
     */
    fun textLayout(value: ProjectionValue): TextLayout =
        if (value === ProjectionValue.Absent) {
            TextLayout.SingleLine
        } else {
            read(value) {
                TextLayout.Multiline(enumeration<TextWrap>(this), int(1..Int.MAX_VALUE), enumeration<TextOverflow>(this), int(0..Int.MAX_VALUE))
            }
        }

    /**
     * Decodes an editor viewport request.
     */
    fun viewport(value: ProjectionValue): TextAreaViewport =
        read(value) {
            when (enumeration<ViewportKind>(this)) {
                ViewportKind.Lines -> TextAreaViewport.Lines(int(1..Int.MAX_VALUE), int(1..Int.MAX_VALUE))
                ViewportKind.Size -> TextAreaViewport.Size(size(value()))
            }
        }

    /**
     * Decodes independent editor frame and decoration appearance.
     */
    fun appearance(value: ProjectionValue): TextInputAppearance =
        if (value === ProjectionValue.Absent) {
            TextInputAppearance.Default
        } else {
            read(value) {
                val normal = image(value())
                val focused = image(value())
                val disabled = image(value())
                val border = insets(value())
                TextInputAppearance.Custom(normal, focused, ArgbColor(int()), disabled, border, ArgbColor(int()))
            }
        }

    /**
     * Decodes the exact standard slot coordinate system.
     */
    fun slot(value: ProjectionValue): SlotBinding =
        read(value) {
            val source = enumeration<SlotBinding.Source>(this)
            val index = int(0..Int.MAX_VALUE)
            when (source) {
                SlotBinding.Source.PlayerInventory -> Slots.playerInventory(index)
                SlotBinding.Source.Container -> Slots.container(index)
                SlotBinding.Source.ActiveMenu -> Slots.activeMenu(index)
            }
        }

    private inline fun <T> read(
        value: ProjectionValue,
        decode: ProjectionFields.() -> T,
    ): T {
        val fields = ProjectionFields(value)
        val result = fields.decode()
        fields.finish()
        return result
    }

    /**
     * Validates one scalar text field.
     */
    fun text(value: ProjectionValue): String = requireNotNull(value as? ProjectionValue.Text).value

    /**
     * Validates one scalar flag field.
     */
    fun flag(value: ProjectionValue): Boolean = requireNotNull(value as? ProjectionValue.Flag).value

    /**
     * Validates one scalar real field.
     */
    fun real(value: ProjectionValue): Double = requireNotNull(value as? ProjectionValue.Real).value

    /**
     * Validates one scalar integer field.
     */
    fun integer(value: ProjectionValue): Int =
        requireNotNull(value as? ProjectionValue.Integer).value.let {
            require(it in 0L..Int.MAX_VALUE.toLong())
            it.toInt()
        }

    private enum class SkinKind { Current, Pixels, Name, Uuid }

    private enum class ViewportKind { Lines, Size }
}
