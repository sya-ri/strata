package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Supplies synchronous physical acknowledgement for already-owned portable textures and views in OpenGL-only families.
 *
 * The render-thread caller must invoke the returned probe only after all native close requests succeed behind the GUI-generation fences.
 * The resource list remains owned by the caller; this probe retains no native object and cannot release storage itself.
 * Construction and polling allocate no native resource and have no native failure path.
 */
@Suppress("UNUSED_PARAMETER")
@JvmSynthetic
internal fun trackPortableDestruction(resources: List<AutoCloseable>): FabricNativeCanvasDestruction = FabricNativeCanvasDestruction { true }
