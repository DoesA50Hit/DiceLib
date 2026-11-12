package org._50dozenhits.dice_library.examples;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org._50dozenhits.dice_library.client.hologram.HologramScreen;
import org._50dozenhits.dice_library.hologram.Holograms.HologramPayload;

// From the wiki page Looks and Custom UIs.
public final class ShopScreen extends HologramScreen {
    public static final ResourceLocation ID = MyMod.id("shop");
    private static final ResourceLocation BACKGROUND = MyMod.id("textures/gui/shop.png");
    private EditBox amount;

    // Register at client setup: CustomUis.hologram(ShopScreen.ID, ShopScreen::new);
    public ShopScreen(HologramPayload hologram) {
        super(hologram, 176, 96);                         // the screen's size in GUI pixels
    }

    // Runs once before the first draw. Widgets are placed in GUI pixels from the screen's top left.
    @Override
    protected void init() {
        // a text box. A click on it gives it the keyboard until Enter, Escape or another click
        amount = addRenderableWidget(new EditBox(font, 8, 44, 60, 18, Component.literal("Amount")));
        amount.setValue("1");
        addRenderableWidget(Button.builder(Component.literal("Buy"), button -> {
            CompoundTag order = new CompoundTag();
            order.putString("amount", amount.getValue());
            send("buy", order);                           // goes to the hologram's onMessage on the server
        }).bounds(76, 43, 50, 20).build());
        // press("leave") presses the panel button with that id, as a click on DiceLib's own panel would
        addRenderableWidget(Button.builder(Component.literal("Leave"), button -> press("leave")).bounds(8, 70, 50, 20).build());
    }

    // Drawn under the widgets every time the screen redraws.
    @Override
    protected void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(BACKGROUND, 0, 0, 0, 0, width, height, 256, 256);
        graphics.drawString(font, "Ferry tickets", 8, 8, 0xFF3A2A0A, false);
        // data() is the tag the server sent, with this player's own coins in it
        graphics.drawString(font, "Coins: " + data().getInt("coins"), 8, 24, 0xFF5A3A0A, false);
    }
}
