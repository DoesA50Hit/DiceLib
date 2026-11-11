package org._50dozenhits.dice_library.client.dialogue;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.DiceClientConfig;
import org._50dozenhits.dice_library.client.ui.CustomUis;
import org._50dozenhits.dice_library.client.ui.CustomUis.Bubble;
import org._50dozenhits.dice_library.client.ui.Look;
import org._50dozenhits.dice_library.client.ui.Looks;
import org._50dozenhits.dice_library.client.ui.Portrait;
import org._50dozenhits.dice_library.client.ui.TypedText;
import org._50dozenhits.dice_library.client.ui.WorldProjection;
import org._50dozenhits.dice_library.dialogue.Speech.SpeechPayload;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Spoken lines on the HUD: the subtitle box above the hotbar, and the speech bubble over the speaker. Lines wait their
 * turn in a short queue. An interrupting line clears it. A line's style picks its look, or a custom speech UI that
 * draws it instead (CustomUis.speech).
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class SpeechHud {
    private static final int MAX_QUEUE = 8;
    // the subtitle sits above the action bar text, which vanilla draws 68 pixels up
    private static final int BOTTOM = 74;
    private static final int TEXT_WIDTH = 260;
    private static final int BUBBLE_WIDTH = 140;
    private static final int PORTRAIT = 24;
    // a voice blip every this many characters
    private static final int BLIP_EVERY = 3;
    private static final double BUBBLE_RANGE = 48;

    private static final Deque<SpeechPayload> QUEUE = new ArrayDeque<>();
    @Nullable
    private static Showing showing;

    private static final class Showing {
        final SpeechPayload line;
        final int length;
        float typed;
        int hold;
        int blips;
        boolean speakerVisible = true;

        Showing(SpeechPayload line) {
            this.line = line;
            this.length = TypedText.length(line.text());
            this.hold = line.ticks();
            this.typed = DiceClientConfig.typeSpeed <= 0 ? length : 0;
        }

        int shown() {
            return Math.min(length, (int) typed);
        }

        boolean done() {
            return shown() >= length;
        }
    }

    @ApiStatus.Internal
    public static void accept(SpeechPayload payload) {
        if (payload.interrupt()) {
            QUEUE.clear();
            start(payload);
            return;
        }
        if (showing == null) {
            start(payload);
            return;
        }
        if (QUEUE.size() >= MAX_QUEUE) QUEUE.pollFirst();
        QUEUE.addLast(payload);
    }

    /**
     * show the rest of the current line now
     */
    public static void skipTyping() {
        if (showing != null) showing.typed = showing.length;
    }

    public static void clear() {
        QUEUE.clear();
        showing = null;
    }

    public static boolean showing() {
        return showing != null;
    }

    private static void start(SpeechPayload payload) {
        showing = new Showing(payload);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || showing == null) return;
        Showing now = showing;
        if (!now.done()) {
            int before = now.shown();
            now.typed += DiceClientConfig.typeSpeed / 20f;
            int after = now.shown();
            if (after != before) {
                int blips = after / BLIP_EVERY;
                if (blips > now.blips) {
                    now.blips = blips;
                    now.line.voice().ifPresent(voice -> mc.getSoundManager().play(SimpleSoundInstance.forUI(voice.value(),
                            now.line.pitch() * Mth.nextFloat(mc.level.random, 0.94f, 1.06f), 0.45f)));
                }
            }
        } else if (--now.hold <= 0) {
            SpeechPayload next = QUEUE.pollFirst();
            if (next != null) start(next);
            else showing = null;
            return;
        }
        Entity speaker = now.line.speaker() < 0 ? null : mc.level.getEntity(now.line.speaker());
        now.speakerVisible = speaker != null && WorldProjection.visible(speaker);
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(DiceLib.id("speech"), SpeechHud::render);
    }

    static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Showing now = showing;
        if (now == null || mc.player == null || mc.level == null || mc.options.hideGui || DialogueScreen.isOpen()) return;
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        Component typed = TypedText.typed(now.line.text(), now.shown());
        Bubble bubble = bubble(now, mc);
        CustomUis.SpeechUi custom = CustomUis.speech(now.line.style());
        if (custom != null) {
            custom.render(graphics, now.line, typed, bubble, partialTick);
            return;
        }
        Look look = Looks.get(now.line.style());
        subtitle(graphics, mc.font, look, now);
        if (bubble != null) bubble(graphics, mc.font, look, now, bubble);
    }

    // where the bubble goes this frame, or null for none
    @Nullable
    private static Bubble bubble(Showing now, Minecraft mc) {
        Entity speaker = now.line.speaker() < 0 ? null : mc.level.getEntity(now.line.speaker());
        if (speaker == null || !now.line.bubble() || !DiceClientConfig.speechBubbles || !now.speakerVisible
                || speaker == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson() || !WorldProjection.ready()) return null;
        Vec3 head = WorldProjection.at(speaker, speaker.getBbHeight() + 0.45);
        WorldProjection.Point point = WorldProjection.project(head);
        if (point == null || !point.onScreen() || point.distance() > BUBBLE_RANGE) return null;
        return new Bubble(point.x(), point.y(), (float) Mth.clamp(10 / point.distance(), 0.55, 1));
    }

    // the dark box above the hotbar: a portrait, the speaker's name and the line typing out
    private static void subtitle(GuiGraphics graphics, Font font, Look look, Showing now) {
        Look.Box box = look.subtitle();
        Component name = now.line.name();
        boolean named = !name.getString().isEmpty();
        int width = Math.max(font.width(name), Math.min(TEXT_WIDTH, font.width(now.line.text())));
        List<FormattedText> wrapped = TypedText.wrap(font, now.line.text(), width);
        List<FormattedCharSequence> lines = TypedText.reveal(wrapped, now.shown());
        int portraitEntity = now.line.speakerPortrait() ? now.line.speaker() : -1;
        boolean portrait = Portrait.shows(now.line.portrait(), portraitEntity);
        int columnHeight = (named ? font.lineHeight + 1 : 0) + lines.size() * font.lineHeight;
        int contentHeight = Math.max(portrait ? PORTRAIT : 0, columnHeight);
        int boxWidth = box.padding() * 2 + (portrait ? PORTRAIT + box.gap() : 0) + width;
        int boxHeight = box.padding() * 2 + contentHeight;
        int x = (graphics.guiWidth() - boxWidth) / 2, y = graphics.guiHeight() - BOTTOM - boxHeight;
        box.background().draw(graphics, x, y, boxWidth, boxHeight);
        int innerX = x + box.padding(), innerY = y + box.padding();
        if (portrait) Portrait.draw(graphics, innerX, innerY + (contentHeight - PORTRAIT) / 2, PORTRAIT, now.line.portrait(), portraitEntity);
        int textX = portrait ? innerX + PORTRAIT + box.gap() : innerX;
        int textY = innerY + (contentHeight - columnHeight) / 2;
        if (named) {
            graphics.drawString(font, name, textX, textY, look.subtitleSpeaker().color(), look.subtitleSpeaker().shadow());
            textY += font.lineHeight + 1;
        }
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, textX, textY, look.subtitleText().color(), look.subtitleText().shadow());
            textY += font.lineHeight;
        }
    }

    // the bubble over the speaker's head, its bottom middle at the point, smaller further away
    private static void bubble(GuiGraphics graphics, Font font, Look look, Showing now, Bubble at) {
        Look.Box box = look.bubble();
        int width = Math.max(1, Math.min(BUBBLE_WIDTH, font.width(now.line.text())));
        List<FormattedText> wrapped = TypedText.wrap(font, now.line.text(), width);
        List<FormattedCharSequence> lines = TypedText.reveal(wrapped, now.shown());
        int boxWidth = width + box.padding() * 2, boxHeight = lines.size() * font.lineHeight + box.padding() * 2;
        graphics.pose().pushPose();
        graphics.pose().translate(at.x(), at.y(), 0);
        graphics.pose().scale(at.scale(), at.scale(), 1);
        graphics.pose().translate(-boxWidth / 2f, -boxHeight, 0);
        box.background().draw(graphics, 0, 0, boxWidth, boxHeight);
        int y = box.padding();
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, box.padding(), y, look.bubbleText().color(), look.bubbleText().shadow());
            y += font.lineHeight;
        }
        graphics.pose().popPose();
    }
}
