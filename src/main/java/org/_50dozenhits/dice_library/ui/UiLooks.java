package org._50dozenhits.dice_library.ui;

import net.minecraft.resources.ResourceLocation;
import org._50dozenhits.dice_library.DiceLib;

/**
 * The ids a spoken line, a dialogue tree, a hologram or a mob panel names as its "style". Each client looks the id up:
 * a custom UI registered for that surface under it (client.ui.CustomUis) draws it itself. Otherwise the
 * look registered under it (client.ui.Looks) gives the colours and backgrounds DiceLib's own UI is drawn with. An id
 * with neither falls back to DEFAULT, so a typo shows the plain look.
 *
 * <pre>{@code
 * Holograms.at(level, pos).style(UiLooks.GLASS)...       "style": "mymod:parchment"
 * }</pre>
 */
public final class UiLooks {
    /**
     * the vanilla look: grey panels with a bevel, dark see-through subtitles, vanilla buttons
     */
    public static final ResourceLocation DEFAULT = DiceLib.id("default");
    /**
     * dark translucent glass with cyan edges
     */
    public static final ResourceLocation GLASS = DiceLib.id("glass");

    private UiLooks() {
    }
}
