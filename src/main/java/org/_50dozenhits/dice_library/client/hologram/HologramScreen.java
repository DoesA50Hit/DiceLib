package org._50dozenhits.dice_library.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.client.ui.PanelUi;
import org._50dozenhits.dice_library.hologram.Holograms.HologramClickPayload;
import org._50dozenhits.dice_library.hologram.Holograms.HologramMessagePayload;
import org._50dozenhits.dice_library.hologram.Holograms.HologramPayload;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A hologram built the way a vanilla Screen is: override init() and add widgets, draw your own background, and
 * handle clicks. The crosshair is the mouse. A left or right click on the hologram goes to the widget under the
 * crosshair (a right click presses a widget as a left click does), and the scroll wheel scrolls it while you aim at
 * it. A click on an EditBox gives it the keyboard. Register one with CustomUis.hologram and name its id as the
 * hologram's style. Client only.
 *
 * <pre>{@code
 * CustomUis.hologram(MyMod.id("shop"), ShopScreen::new);
 *
 * public final class ShopScreen extends HologramScreen {
 *     public ShopScreen(HologramPayload hologram) { super(hologram, 176, 120); }
 *
 *     protected void init() {
 *         addRenderableWidget(Button.builder(Component.literal("Buy"), button -> send("buy", new CompoundTag()))
 *                 .bounds(8, 92, 60, 20).build());
 *     }
 *
 *     protected void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
 *         graphics.blit(MyMod.id("textures/gui/shop.png"), 0, 0, 0, 0, width, height, 256, 256);
 *     }
 * }
 * }</pre>
 *
 * <p>What the screen may do on the server is still the server's call: press(id) goes through the same checks as any
 * hologram button, and send(action, data) reaches the hologram's onMessage listener only for a player who is viewing
 * it, may interact with it and stands in reach. Sliders take their value from where they are clicked. There is no
 * dragging. It redraws when the crosshair moves on it, after a click, and whenever redraw() is called.
 */
public abstract class HologramScreen implements PanelUi {
    protected final Minecraft minecraft = Minecraft.getInstance();
    protected final Font font = minecraft.font;
    /**
     * its size in GUI pixels. The hologram's quad in the world is this size at the hologram's scale
     */
    protected final int width, height;
    private HologramPayload hologram;
    private final List<Renderable> renderables = new ArrayList<>();
    private final List<GuiEventListener> children = new ArrayList<>();
    private boolean built;
    private boolean dirty = true;
    private boolean animated;

    protected HologramScreen(HologramPayload hologram, int width, int height) {
        this.hologram = hologram;
        this.width = width;
        this.height = height;
    }

    /**
     * Adds the widgets. Runs once before the first draw, and again on rebuild().
     */
    protected abstract void init();

    /**
     * Drawn under the widgets. Blit textures and draw text here.
     */
    protected void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * once a client tick
     */
    protected void onTick() {
    }

    /**
     * The server sent the hologram again (new data or a new panel). The screen and its state are kept. rebuild() here
     * when the widgets depend on the data.
     */
    protected void onData(CompoundTag data) {
    }

    protected <T extends GuiEventListener & Renderable> T addRenderableWidget(T widget) {
        renderables.add(widget);
        children.add(widget);
        return widget;
    }

    protected <T extends Renderable> T addRenderableOnly(T renderable) {
        renderables.add(renderable);
        return renderable;
    }

    protected <T extends GuiEventListener> T addWidget(T listener) {
        children.add(listener);
        return listener;
    }

    protected void clearWidgets() {
        renderables.clear();
        children.clear();
    }

    /**
     * clears the widgets and runs init() again
     */
    protected void rebuild() {
        clearWidgets();
        init();
        redraw();
    }

    /**
     * draws it again on the next frame
     */
    protected void redraw() {
        dirty = true;
    }

    /**
     * true draws it again every frame, for a screen that animates. False (the default) only when something changed
     */
    protected void animated(boolean animated) {
        this.animated = animated;
    }

    /**
     * everything the server sent: the panel, the greyed-out buttons, the data tag, the entity it follows
     */
    public HologramPayload hologram() {
        return hologram;
    }

    /**
     * the hologram's data tag, with whatever the server set for this player alone laid over it
     */
    public CompoundTag data() {
        return hologram.data();
    }

    /**
     * Presses a button of the hologram's panel on the server, as a click on DiceLib's own UI would. False, and nothing
     * sent, when that button is greyed out for this player.
     */
    protected boolean press(String button) {
        if (hologram.disabled().contains(button)) return false;
        PacketDistributor.sendToServer(new HologramClickPayload(hologram.id(), hologram.version(), button));
        return true;
    }

    /**
     * Sends your own message to the hologram's onMessage listener on the server.
     */
    protected void send(String action, CompoundTag data) {
        PacketDistributor.sendToServer(new HologramMessagePayload(hologram.id(), action, data));
    }

    /**
     * Gives a text box the keyboard until Enter, Escape or a click. A click on an EditBox calls this already. Call it
     * yourself to start typing from somewhere else, such as a button.
     */
    protected void type(EditBox box) {
        minecraft.setScreen(new Typing(this, box));
    }

    // ---- PanelUi ----

    @Override
    public final int width() {
        return width;
    }

    @Override
    public final int height() {
        return height;
    }

    private void build() {
        if (built) return;
        built = true;
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        build();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        for (Renderable renderable : List.copyOf(renderables)) renderable.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        build();
        // Vanilla widgets only answer the left button, and a right click is how holograms have always been pressed,
        // so a right click nothing took is offered again as a left one.
        if (!click(mouseX, mouseY, button) && button != 0) click(mouseX, mouseY, 0);
        redraw();
        return true;
    }

    private boolean click(int mouseX, int mouseY, int button) {
        for (GuiEventListener child : List.copyOf(children)) {
            if (!child.mouseClicked(mouseX, mouseY, button)) continue;
            if (child instanceof EditBox box) type(box);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(int mouseX, int mouseY, double amount) {
        build();
        for (GuiEventListener child : List.copyOf(children)) {
            if (child.mouseScrolled(mouseX, mouseY, 0, amount)) {
                redraw();
                return true;
            }
        }
        return false;
    }

    @Override
    public final boolean tick() {
        build();
        onTick();
        boolean was = dirty;
        dirty = false;
        return was;
    }

    @Override
    public final boolean animated() {
        return animated;
    }

    @Override
    public boolean update(HologramPayload newer) {
        hologram = newer;
        onData(newer.data());
        redraw();
        return true;
    }

    @Override
    public void removed() {
        if (minecraft.screen instanceof Typing typing && typing.owner == this) minecraft.setScreen(null);
    }

    // The keyboard for one text box. It draws nothing over the world but a hint, so the hologram stays in view.
    private static final class Typing extends Screen {
        private final HologramScreen owner;
        private final EditBox box;

        Typing(HologramScreen owner, EditBox box) {
            super(Component.empty());
            this.owner = owner;
            this.box = box;
        }

        @Override
        protected void init() {
            box.setFocused(true);
            owner.redraw();
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.drawCenteredString(font, Component.translatable("dicelib.hologram.typing"), width / 2, height - 48, 0xFFFFFFFF);
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                onClose();
                return true;
            }
            box.keyPressed(key, scanCode, modifiers);
            owner.redraw();
            return true;
        }

        @Override
        public boolean charTyped(char character, int modifiers) {
            box.charTyped(character, modifiers);
            owner.redraw();
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            onClose();
            return true;
        }

        @Override
        public void removed() {
            box.setFocused(false);
            owner.redraw();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
