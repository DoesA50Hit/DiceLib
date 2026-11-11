package org._50dozenhits.dice_library.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import org._50dozenhits.dice_library.hologram.Holograms.HologramPayload;
import org.jetbrains.annotations.Nullable;

/**
 * What a hologram or a mob panel shows: a small piece of UI drawn with plain GuiGraphics calls, the way a vanilla
 * Screen draws itself. DiceLib's own is PanelRenderer. Register your own with CustomUis. Coordinates are GUI
 * pixels from the top left. A hologram draws it into a texture and redraws only when tick() says so or the crosshair
 * moves. A mob panel draws it every frame. For a hologram built like a screen, with widgets, extend HologramScreen.
 * Client only.
 */
public interface PanelUi {
    int width();

    int height();

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    // the id of the button at a point, sent to the server when a hologram's button is clicked. Null for none
    @Nullable
    default String buttonAt(int mouseX, int mouseY) {
        return null;
    }

    // once a client tick. True when something it shows changed and a hologram should redraw it
    default boolean tick() {
        return false;
    }

    /**
     * A click on a hologram with the crosshair at this point: button 0 is the left (attack) button, 1 the right (use)
     * button. True when the UI took it. A left click it doesn't take goes on to the world behind, and a right click
     * it doesn't take presses the button that buttonAt names.
     */
    default boolean mouseClicked(int mouseX, int mouseY, int button) {
        return false;
    }

    /**
     * The scroll wheel turned while the crosshair was at this point of a hologram. True when the UI took it, which
     * keeps the hotbar from moving.
     */
    default boolean mouseScrolled(int mouseX, int mouseY, double amount) {
        return false;
    }

    /**
     * The server sent the hologram again. True keeps this UI, and whatever state it holds, for the new content. False
     * (the default) has a new UI made.
     */
    default boolean update(HologramPayload newer) {
        return false;
    }

    /**
     * true has a hologram draw it again every frame instead of only when something changed
     */
    default boolean animated() {
        return false;
    }

    /**
     * the hologram it drew is gone, or another UI took its place
     */
    default void removed() {
    }
}
