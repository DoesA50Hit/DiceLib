package org._50dozenhits.dice_library.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * How DiceLib's own UI is drawn: the box behind each surface (holograms, mob panels, the dialogue screen, subtitles,
 * speech bubbles), the colours of its text, dividers, bars and buttons. Register one under an id with Looks.register
 * and name that id as a style (UiLooks). Build one from DEFAULT and change what differs:
 *
 * <pre>{@code
 * Looks.register(MyMod.id("parchment"), Looks.DEFAULT.toBuilder()
 *         .hologram(new Look.Box(Look.Background.sprite(MyMod.id("parchment")), 8, 4))
 *         .text(new Look.Text(0xFF3B2A14, false)).build());
 * }</pre>
 *
 * <p>Colours are ARGB: 0xFF404040 is opaque grey, 0x30000000 a faint black. Client only.
 */
public record Look(Box hologram, Box mobPanel, Box dialogue, Box subtitle, Box bubble,
                   Text title, Text text, Text dim, Text subtitleSpeaker, Text subtitleText, Text bubbleText,
                   int divider, Background bar, int barHeight, Buttons button, int buttonHeight, int choiceHeight) {

    /**
     * a background with the room left inside it (padding) and between what it holds (gap), in GUI pixels
     */
    public record Box(Background background, int padding, int gap) {
    }

    public record Text(int color, boolean shadow) {
    }

    public record Buttons(Background normal, Background hover, Background disabled, Text text, Text disabledText) {
    }

    /**
     * What fills a rectangle. The factories cover vanilla's own shapes. Any lambda works too.
     */
    @FunctionalInterface
    public interface Background {
        void draw(GuiGraphics graphics, int x, int y, int width, int height);

        Background NONE = (graphics, x, y, width, height) -> {
        };

        static Background rect(int color) {
            return (graphics, x, y, width, height) -> graphics.fill(x, y, x + width, y + height, color);
        }

        // vanilla's window: grey, a white bevel top and left, a dark one bottom and right, a black outline with
        // rounded corners
        static Background panel() {
            return (graphics, x, y, width, height) -> {
                if (width < 6 || height < 6) {
                    graphics.fill(x, y, x + width, y + height, 0xFFC6C6C6);
                    return;
                }
                int x1 = x + width, y1 = y + height;
                int black = 0xFF000000, white = 0xFFFFFFFF, shade = 0xFF555555;
                graphics.fill(x + 2, y, x1 - 2, y + 1, black);
                graphics.fill(x + 2, y1 - 1, x1 - 2, y1, black);
                graphics.fill(x, y + 2, x + 1, y1 - 2, black);
                graphics.fill(x1 - 1, y + 2, x1, y1 - 2, black);
                graphics.fill(x + 1, y + 1, x + 2, y + 2, black);
                graphics.fill(x1 - 2, y + 1, x1 - 1, y + 2, black);
                graphics.fill(x + 1, y1 - 2, x + 2, y1 - 1, black);
                graphics.fill(x1 - 2, y1 - 2, x1 - 1, y1 - 1, black);
                graphics.fill(x + 2, y + 1, x1 - 3, y + 3, white);
                graphics.fill(x + 1, y + 2, x + 3, y1 - 3, white);
                graphics.fill(x + 3, y1 - 3, x1 - 2, y1 - 1, shade);
                graphics.fill(x1 - 3, y + 3, x1 - 1, y1 - 2, shade);
                graphics.fill(x + 3, y + 3, x1 - 3, y1 - 3, 0xFFC6C6C6);
            };
        }

        // a sunken box, like an inventory slot
        static Background inset() {
            return (graphics, x, y, width, height) -> {
                graphics.fill(x, y, x + width, y + height, 0xFF8B8B8B);
                graphics.fill(x, y, x + width - 1, y + 1, 0xFF373737);
                graphics.fill(x, y, x + 1, y + height - 1, 0xFF373737);
                graphics.fill(x + 1, y + height - 1, x + width, y + height, 0xFFFFFFFF);
                graphics.fill(x + width - 1, y + 1, x + width, y + height, 0xFFFFFFFF);
            };
        }

        // a rectangle with rounded corners of `radius` and an edge `border` pixels wide (0 for none)
        static Background rounded(int fill, int radius, int border, int borderColor) {
            return (graphics, x, y, width, height) -> {
                int r = Math.min(radius, Math.min(width, height) / 2);
                int b = Math.min(border, Math.min(width, height) / 2);
                // the rows the corners shape. Between them every row is the same, drawn as three rectangles
                int edge = Math.max(r, b);
                for (int row = 0; row < height; row++) {
                    if (row == edge && height - edge > edge) {
                        int y0 = y + edge, y1 = y + height - edge;
                        if (b > 0) {
                            graphics.fill(x, y0, x + b, y1, borderColor);
                            graphics.fill(x + width - b, y0, x + width, y1, borderColor);
                        }
                        graphics.fill(x + b, y0, x + width - b, y1, fill);
                        row = height - edge - 1;
                        continue;
                    }
                    int outer = corner(row, height, r);
                    boolean inside = row >= b && row < height - b;
                    int inner = inside ? corner(row - b, height - 2 * b, Math.max(0, r - b)) + b : -1;
                    if (inner < 0 || inner * 2 >= width) {
                        graphics.fill(x + outer, y + row, x + width - outer, y + row + 1, b > 0 ? borderColor : fill);
                        continue;
                    }
                    if (b > 0) {
                        graphics.fill(x + outer, y + row, x + inner, y + row + 1, borderColor);
                        graphics.fill(x + width - inner, y + row, x + width - outer, y + row + 1, borderColor);
                    }
                    graphics.fill(x + inner, y + row, x + width - inner, y + row + 1, fill);
                }
            };
        }

        // a GUI sprite (assets/<ns>/textures/gui/sprites/<path>.png), stretched or nine-sliced as its .mcmeta says,
        // so a resource pack can retexture it
        static Background sprite(ResourceLocation sprite) {
            return (graphics, x, y, width, height) -> graphics.blitSprite(sprite, x, y, width, height);
        }

        // how far a row of a shape with rounded corners starts in from its side
        private static int corner(int row, int height, int radius) {
            if (radius <= 0) return 0;
            int into = row < radius ? radius - row : row >= height - radius ? row - (height - radius) + 1 : 0;
            if (into == 0) return 0;
            double dy = into - 0.5;
            return (int) Math.round(radius - Math.sqrt(Math.max(0, radius * radius - dy * dy)));
        }
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private Box hologram, mobPanel, dialogue, subtitle, bubble;
        private Text title, text, dim, subtitleSpeaker, subtitleText, bubbleText;
        private int divider;
        private Background bar;
        private int barHeight;
        private Buttons button;
        private int buttonHeight, choiceHeight;

        private Builder(Look from) {
            hologram = from.hologram;
            mobPanel = from.mobPanel;
            dialogue = from.dialogue;
            subtitle = from.subtitle;
            bubble = from.bubble;
            title = from.title;
            text = from.text;
            dim = from.dim;
            subtitleSpeaker = from.subtitleSpeaker;
            subtitleText = from.subtitleText;
            bubbleText = from.bubbleText;
            divider = from.divider;
            bar = from.bar;
            barHeight = from.barHeight;
            button = from.button;
            buttonHeight = from.buttonHeight;
            choiceHeight = from.choiceHeight;
        }

        public Builder hologram(Box box) {
            hologram = box;
            return this;
        }

        public Builder mobPanel(Box box) {
            mobPanel = box;
            return this;
        }

        public Builder dialogue(Box box) {
            dialogue = box;
            return this;
        }

        public Builder subtitle(Box box) {
            subtitle = box;
            return this;
        }

        public Builder bubble(Box box) {
            bubble = box;
            return this;
        }

        /**
         * the same background for every box, keeping each one's padding and gap
         */
        public Builder backgrounds(Background background) {
            hologram = new Box(background, hologram.padding(), hologram.gap());
            mobPanel = new Box(background, mobPanel.padding(), mobPanel.gap());
            dialogue = new Box(background, dialogue.padding(), dialogue.gap());
            subtitle = new Box(background, subtitle.padding(), subtitle.gap());
            bubble = new Box(background, bubble.padding(), bubble.gap());
            return this;
        }

        /**
         * titles, and the speaker's name on the dialogue screen
         */
        public Builder title(Text style) {
            title = style;
            return this;
        }

        public Builder text(Text style) {
            text = style;
            return this;
        }

        /**
         * labels over bars, health numbers, the dialogue screen's "click to go on"
         */
        public Builder dim(Text style) {
            dim = style;
            return this;
        }

        public Builder subtitleSpeaker(Text style) {
            subtitleSpeaker = style;
            return this;
        }

        public Builder subtitleText(Text style) {
            subtitleText = style;
            return this;
        }

        public Builder bubbleText(Text style) {
            bubbleText = style;
            return this;
        }

        public Builder divider(int color) {
            divider = color;
            return this;
        }

        /**
         * behind a bar's fill, which is drawn one pixel in from its edge
         */
        public Builder bar(Background background, int height) {
            bar = background;
            barHeight = height;
            return this;
        }

        /**
         * hologram buttons and dialogue choices
         */
        public Builder button(Buttons buttons) {
            button = buttons;
            return this;
        }

        public Builder buttonHeight(int buttonHeight, int choiceHeight) {
            this.buttonHeight = buttonHeight;
            this.choiceHeight = choiceHeight;
            return this;
        }

        public Look build() {
            return new Look(hologram, mobPanel, dialogue, subtitle, bubble, title, text, dim, subtitleSpeaker, subtitleText, bubbleText,
                    divider, bar, barHeight, button, buttonHeight, choiceHeight);
        }
    }
}
