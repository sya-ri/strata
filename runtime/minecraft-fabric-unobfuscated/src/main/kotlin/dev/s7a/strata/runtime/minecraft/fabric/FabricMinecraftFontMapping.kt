package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.resource.ResourceId
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style

/**
 * Creates an independent native style carrying one resource-backed font description.
 *
 * The synchronous conversion performs no resource lookup, retains no runtime service, and is safe without a loaded client.
 *
 * @param font validated structural font identifier.
 * @return immutable native style owned by the caller.
 */
@JvmSynthetic
internal fun mapMinecraftFont(font: ResourceId): Style = Style.EMPTY.withFont(FontDescription.Resource(minecraftResourceLocation(font.namespace, font.path)))
