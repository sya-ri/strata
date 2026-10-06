# Inventory-style colors and bevels

Use this palette only when the requested screen should resemble the vanilla inventory; it is not a requirement for custom themes.
These opaque RGB values were sampled from Java 26.2 [`textures/gui/container/inventory.png`](https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/26.2/assets/minecraft/textures/gui/container/inventory.png), fetched with the Minecraft evidence tools.
Treat this as a versioned reference and inspect the target version's asset or active resource pack before claiming an exact match.

| Surface | Upper/left edge | Lower/right edge | Face and intermediate corner color |
| --- | --- | --- | --- |
| Raised inventory panel | `#FFFFFF`, 2px | `#555555`, 2px | `#C6C6C6` |
| Recessed slot | `#373737`, 1px | `#FFFFFF`, 1px | `#8B8B8B` |

The raised panel also has a black `#000000` outer contour with stepped transparent corners.
The upper-right and lower-left joins use the intermediate color, rather than letting one straight edge overwrite the other.
Keep the raised-panel and recessed-slot shadow colors distinct.

Reuse a suitable standard component or resource-pack image first.
For a custom-sized panel, retain one small immutable image and use `imageBackground(source, border = Insets.all(...))` to preserve its corners with nine-slice scaling.
Preserve the panel's 4px corner regions; specify layout padding separately and do not fill its transparent corners with another opaque background.
A recessed slot can use a 3×3 source with a 1px border: `DDI / DIH / IHH`, where D is `#373737`, I is `#8B8B8B`, and H is `#FFFFFF`.
Plain overlapping rectangles lose the intermediate corner pixels.
Verify the resulting edges and joins at the actual viewport and GUI scale, including fractional scaling, without changing input ownership to implement decoration.
