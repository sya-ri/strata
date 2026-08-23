package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.SharedConstants

/** Returns the loaded release name through Minecraft 1.21.5's JavaBean world-version contract. */
internal fun loadedMinecraftVersion(): String = SharedConstants.getCurrentVersion().name
