package org._50dozenhits.dice_library.client.dialogue;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.DiceClientConfig;
import org._50dozenhits.dice_library.client.ui.CustomUis;
import org._50dozenhits.dice_library.client.ui.Look;
import org._50dozenhits.dice_library.client.ui.Looks;
import org._50dozenhits.dice_library.client.ui.Portrait;
import org._50dozenhits.dice_library.client.ui.TypedText;
import org._50dozenhits.dice_library.dialogue.Dialogues;
import org._50dozenhits.dice_library.dialogue.Dialogues.DialogueEndPayload;
import org._50dozenhits.dice_library.dialogue.Dialogues.DialogueNodePayload;
import org._50dozenhits.dice_library.dialogue.Dialogues.DialogueReplyPayload;
import org._50dozenhits.dice_library.dialogue.Dialogues.Option;
import org._50dozenhits.dice_library.dialogue.Dialogues.Said;
import org.lwjgl.glfw.GLFW;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The conversation box along the bottom of the screen. The game keeps running behind it. Each line types out. A click,
 * Space or Enter finishes it, then shows the next. After the last line come the choices (click, or keys 1 to 9), or a
 * prompt to go on. Escape closes it when the node allows. It only shows what the server sent and answers with the step
 * it showed, so the server decides everything. A tree whose style names a custom dialogue UI (CustomUis.dialogue)
 * opens that screen instead.
 */
public final class DialogueScreen {
    private static final int MAX_WIDTH = 360;
    private static final int PORTRAIT = 40;
    private static final int BLIP_EVERY = 3;
    // clear of the hotbar
    private static final int BOTTOM = 34;

    /**
     * What your own dialogue screen implements, so the next node of the same dialogue reaches it.
     */
    public interface View {
        // the style it was opened for. A node with another style opens a new screen
        ResourceLocation style();

        void show(DialogueNodePayload node);

        // the server ended the dialogue: close without answering
        void endFromServer();
    }

    @ApiStatus.Internal
    public static void accept(DialogueNodePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof View view && view.style().equals(payload.style())) {
            view.show(payload);
            return;
        }
        mc.setScreen(create(payload));
    }

    @ApiStatus.Internal
    public static void end(DialogueEndPayload payload) {
        if (Minecraft.getInstance().screen instanceof View view) view.endFromServer();
    }

    public static boolean isOpen() {
        return Minecraft.getInstance().screen instanceof View;
    }

    public static Screen create(DialogueNodePayload payload) {
        CustomUis.DialogueUi custom = CustomUis.dialogue(payload.style());
        if (custom != null) {
            Screen screen = custom.create(payload);
            if (!(screen instanceof View)) throw new IllegalStateException("The dialogue UI " + payload.style() + " must implement DialogueScreen.View");
            return screen;
        }
        Box box = new Box(payload);
        box.show(payload);
        return box;
    }

    /**
     * Answers the node on screen: a choice's index, Dialogues.ADVANCE to go on, or Dialogues.CLOSE to leave.
     */
    public static void reply(DialogueNodePayload node, int choice) {
        PacketDistributor.sendToServer(new DialogueReplyPayload(node.step(), choice));
    }

    // DiceLib's own box, drawn in the tree's look.
    private static final class Box extends Screen implements View {
        private final ResourceLocation style;
        private final Look look;
        private DialogueNodePayload node;
        private int line;
        private float typed;
        private int blips;
        private boolean answered;
        private boolean ended;
        // laid out each frame. The choices' rows, for clicks
        private int boxX, boxY, boxWidth, choicesY, choiceWidth, choicesX;

        private Box(DialogueNodePayload node) {
            super(Component.translatable("dicelib.ui.dialogue"));
            this.node = node;
            this.style = node.style();
            this.look = Looks.get(style);
        }

        @Override
        public ResourceLocation style() {
            return style;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return node.canClose();
        }

        @Override
        public void show(DialogueNodePayload payload) {
            node = payload;
            line = 0;
            answered = false;
            if (payload.again() && !payload.lines().isEmpty()) {
                line = payload.lines().size() - 1;
                typed = length();
                return;
            }
            startLine();
        }

        private Said said() {
            return node.lines().isEmpty() ? new Said(node.name(), Component.empty()) : node.lines().get(Math.min(line, node.lines().size() - 1));
        }

        private int length() {
            return TypedText.length(said().text());
        }

        private boolean typedOut() {
            return typed >= length();
        }

        private boolean lastLine() {
            return line >= node.lines().size() - 1;
        }

        private boolean choosing() {
            return typedOut() && lastLine() && !node.choices().isEmpty();
        }

        private void startLine() {
            typed = DiceClientConfig.typeSpeed <= 0 ? length() : 0;
            blips = 0;
        }

        @Override
        public void tick() {
            super.tick();
            if (typedOut()) return;
            int before = (int) typed;
            typed = Math.min(length(), typed + DiceClientConfig.typeSpeed / 20f);
            int after = (int) typed;
            if (after == before) return;
            int count = after / BLIP_EVERY;
            Minecraft mc = Minecraft.getInstance();
            if (count > blips && mc.level != null) {
                blips = count;
                node.voice().ifPresent(voice -> mc.getSoundManager().play(SimpleSoundInstance.forUI(voice.value(),
                        node.pitch() * Mth.nextFloat(mc.level.random, 0.94f, 1.06f), 0.45f)));
            }
        }

        /**
         * the game stays in view behind the box
         */
        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            Font font = this.font;
            Look.Box frame = look.dialogue();
            int pad = frame.padding(), gap = frame.gap();
            boxWidth = Math.min(MAX_WIDTH, width - 24);
            boxX = (width - boxWidth) / 2;
            int inner = boxWidth - pad * 2;

            Said said = said();
            boolean portrait = Portrait.shows(node.portrait(), node.speakerPortrait() ? node.speaker() : -1);
            int column = inner - (portrait ? PORTRAIT + 6 : 0);
            List<FormattedCharSequence> name = said.name().getString().isEmpty() ? List.of() : font.split(said.name(), column);
            List<FormattedText> wrapped = TypedText.wrap(font, said.text(), column);
            List<FormattedCharSequence> text = TypedText.reveal(wrapped, (int) typed);
            int columnHeight = name.size() * font.lineHeight + (name.isEmpty() ? 0 : 2) + text.size() * font.lineHeight;
            int top = Math.max(portrait ? PORTRAIT : 0, columnHeight);

            boolean choosing = choosing();
            boolean hint = typedOut() && !choosing;
            List<Option> options = node.choices();
            int height = pad + top + (hint ? gap + font.lineHeight : 0)
                    + (choosing ? gap + options.size() * look.choiceHeight() + (options.size() - 1) * 2 : 0) + pad;
            boxY = height > 0 ? this.height - BOTTOM - height : 0;

            frame.background().draw(graphics, boxX, boxY, boxWidth, height);
            int x = boxX + pad, y = boxY + pad;
            if (portrait) Portrait.draw(graphics, x, y, PORTRAIT, node.portrait(), node.speakerPortrait() ? node.speaker() : -1);
            int textX = portrait ? x + PORTRAIT + 6 : x, textY = y;
            for (FormattedCharSequence one : name) {
                graphics.drawString(font, one, textX, textY, look.title().color(), look.title().shadow());
                textY += font.lineHeight;
            }
            if (!name.isEmpty()) textY += 2;
            for (FormattedCharSequence one : text) {
                graphics.drawString(font, one, textX, textY, look.text().color(), look.text().shadow());
                textY += font.lineHeight;
            }
            y += top;
            if (hint) {
                y += gap;
                Component prompt = Component.translatable(!lastLine() ? "dicelib.dialogue.more" : node.last() ? "dicelib.dialogue.close" : "dicelib.dialogue.continue");
                graphics.drawString(font, prompt, x + inner - font.width(prompt), y, look.dim().color(), look.dim().shadow());
                y += font.lineHeight;
            }
            choicesX = x;
            choiceWidth = inner;
            choicesY = y + gap;
            if (choosing) {
                Look.Buttons buttons = look.button();
                for (int i = 0; i < options.size(); i++) {
                    Option option = options.get(i);
                    int rowY = choicesY + i * (look.choiceHeight() + 2);
                    boolean open = option.open();
                    boolean hovered = open && over(mouseX, mouseY, i);
                    (!open ? buttons.disabled() : hovered ? buttons.hover() : buttons.normal()).draw(graphics, x, rowY, inner, look.choiceHeight());
                    MutableComponent label = Component.literal((i + 1) + ". ").append(option.text());
                    if (!open) label.withStyle(ChatFormatting.DARK_GRAY);
                    List<FormattedCharSequence> lines = font.split(label, inner - 8);
                    FormattedCharSequence first = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
                    Look.Text style = open ? buttons.text() : buttons.disabledText();
                    graphics.drawString(font, first, x + (inner - font.width(first)) / 2, rowY + (look.choiceHeight() - 8) / 2, style.color(), style.shadow());
                }
            }
        }

        private boolean over(double mouseX, double mouseY, int index) {
            int rowY = choicesY + index * (look.choiceHeight() + 2);
            return mouseX >= choicesX && mouseX < choicesX + choiceWidth && mouseY >= rowY && mouseY < rowY + look.choiceHeight();
        }

        // finish the line, show the next one, or move on
        private void advance() {
            if (answered) return;
            if (!typedOut()) {
                typed = length();
                return;
            }
            if (!lastLine()) {
                line++;
                startLine();
                return;
            }
            if (!node.choices().isEmpty()) return;
            answer(Dialogues.ADVANCE);
        }

        private void choose(int index) {
            if (answered || !typedOut() || !lastLine() || index >= node.choices().size() || !node.choices().get(index).open()) return;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
            answer(index);
        }

        private void answer(int choice) {
            answered = true;
            reply(node, choice);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
            if (choosing()) {
                for (int i = 0; i < node.choices().size(); i++) {
                    if (over(mouseX, mouseY, i)) {
                        choose(i);
                        return true;
                    }
                }
                return true;
            }
            advance();
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                advance();
                return true;
            }
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                choose(key - GLFW.GLFW_KEY_1);
                return true;
            }
            return super.keyPressed(key, scanCode, modifiers);
        }

        @Override
        public void endFromServer() {
            ended = true;
            onClose();
        }

        @Override
        public void removed() {
            super.removed();
            if (!ended && node.canClose() && Minecraft.getInstance().getConnection() != null) reply(node, Dialogues.CLOSE);
        }
    }

    private DialogueScreen() {
    }
}
