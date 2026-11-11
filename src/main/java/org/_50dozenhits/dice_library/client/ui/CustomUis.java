package org._50dozenhits.dice_library.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org._50dozenhits.dice_library.dialogue.Dialogues.DialogueNodePayload;
import org._50dozenhits.dice_library.dialogue.Speech.SpeechPayload;
import org._50dozenhits.dice_library.hologram.Holograms.HologramPayload;
import org._50dozenhits.dice_library.hologram.MobPanels.MobPanelPayload;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Your own UIs for DiceLib's surfaces, in place of its built-in ones. Each is registered under an id. A hologram,
 * mob panel, dialogue tree or spoken line that names that id as its style (UiLooks) is drawn by it. Ids with nothing
 * registered here are looks (Looks) for the built-in UI. Register at client setup. Draw with plain GuiGraphics, or
 * with any UI library the mod uses. Client only.
 *
 * <pre>{@code
 * CustomUis.hologram(MyMod.id("shop"), hologram -> new ShopPanel(hologram.data(), hologram.width()));
 * CustomUis.dialogue(MyMod.id("letter"), node -> new LetterScreen(node));
 * }</pre>
 */
public final class CustomUis {
    /**
     * A PanelUi for one hologram, from what the server sent: its width, panel, disabled buttons and data tag.
     */
    @FunctionalInterface
    public interface HologramUi {
        PanelUi create(HologramPayload hologram);
    }

    /**
     * A PanelUi for one mob panel. It sits on the HUD by its mob, sized by its width and height.
     */
    @FunctionalInterface
    public interface MobPanelUi {
        PanelUi create(MobPanelPayload panel);
    }

    /**
     * A screen for a dialogue. It must implement DialogueScreen.View: the next node of the same dialogue arrives
     * through show, and DialogueScreen.reply answers the server.
     */
    @FunctionalInterface
    public interface DialogueUi {
        Screen create(DialogueNodePayload node);
    }

    /**
     * Draws the line showing: {@code typed} is as much of its text as has typed out so far, {@code bubble} where a bubble over
     * the speaker would go (null when there should be none). Called every frame on the HUD.
     */
    @FunctionalInterface
    public interface SpeechUi {
        void render(GuiGraphics graphics, SpeechPayload line, Component typed, @Nullable Bubble bubble, float partialTick);
    }

    /**
     * a point on the GUI above the speaker's head, and how small a bubble there should be drawn (1 up close)
     */
    public record Bubble(float x, float y, float scale) {
    }

    private static final Map<ResourceLocation, HologramUi> HOLOGRAMS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, MobPanelUi> MOB_PANELS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, DialogueUi> DIALOGUES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, SpeechUi> SPEECH = new ConcurrentHashMap<>();

    public static void hologram(ResourceLocation id, HologramUi ui) {
        HOLOGRAMS.put(id, ui);
    }

    public static void mobPanel(ResourceLocation id, MobPanelUi ui) {
        MOB_PANELS.put(id, ui);
    }

    public static void dialogue(ResourceLocation id, DialogueUi ui) {
        DIALOGUES.put(id, ui);
    }

    public static void speech(ResourceLocation id, SpeechUi ui) {
        SPEECH.put(id, ui);
    }

    @Nullable
    public static HologramUi hologram(ResourceLocation id) {
        return HOLOGRAMS.get(id);
    }

    @Nullable
    public static MobPanelUi mobPanel(ResourceLocation id) {
        return MOB_PANELS.get(id);
    }

    @Nullable
    public static DialogueUi dialogue(ResourceLocation id) {
        return DIALOGUES.get(id);
    }

    @Nullable
    public static SpeechUi speech(ResourceLocation id) {
        return SPEECH.get(id);
    }

    private CustomUis() {
    }
}
