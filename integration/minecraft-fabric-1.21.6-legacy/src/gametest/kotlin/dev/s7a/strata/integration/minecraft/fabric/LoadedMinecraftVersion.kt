package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.SharedConstants

/** Returns the loaded release name through the record-style world-version contract introduced in Minecraft 1.21.6. */
internal fun loadedMinecraftVersion(): String = SharedConstants.getCurrentVersion().name()
