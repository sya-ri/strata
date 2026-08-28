package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import net.minecraft.network.chat.Component

/**
 * Converts one platform-neutral text value into an independent Minecraft component tree.
 *
 * The conversion is synchronous, retains no [UiText] wrapper or part list, and does not require a loaded client or language manager.
 *
 * @param text unresolved text value.
 * @return an equivalent mutable Minecraft component owned by the caller.
 * @throws IllegalArgumentException when [UiText.Platform] has no safe common-to-Minecraft adapter.
 */
@JvmSynthetic
internal fun mapMinecraftText(text: UiText): Component =
    when (text) {
        is UiText.Literal -> Component.literal(text.value)
        is UiText.WithFont -> mapFontMinecraftText(text)
        is UiText.Translated -> mapTranslatedMinecraftText(text)
        is UiText.Concatenated -> text.parts.fold(Component.empty()) { result, part -> result.append(mapMinecraftText(part)) }
        is UiText.Platform -> throw IllegalArgumentException("Platform text has no safe Component adapter.")
    }

private fun mapFontMinecraftText(text: UiText.WithFont): Component {
    val component = mapMinecraftText(text.text).copy()
    return component.setStyle(component.getStyle().applyTo(mapMinecraftFont(text.font)))
}

private fun mapTranslatedMinecraftText(text: UiText.Translated): Component {
    val arguments = text.arguments.map(::mapMinecraftTextArgument).toTypedArray()
    return when (val fallback = text.fallback) {
        TranslationFallback.UseKey -> Component.translatable(text.key, *arguments)
        is TranslationFallback.Literal -> Component.translatableWithFallback(text.key, fallback.value, *arguments)
    }
}

private fun mapMinecraftTextArgument(value: UiTextArgument): Any =
    when (value) {
        is UiTextArgument.Text -> mapMinecraftText(value.value)
        is UiTextArgument.StringValue -> value.value
        is UiTextArgument.IntValue -> value.value
        is UiTextArgument.LongValue -> value.value
        is UiTextArgument.FloatValue -> value.value
        is UiTextArgument.DoubleValue -> value.value
        is UiTextArgument.BooleanValue -> value.value
    }
