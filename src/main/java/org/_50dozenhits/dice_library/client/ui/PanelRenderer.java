package org._50dozenhits.dice_library.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org._50dozenhits.dice_library.client.ui.Look.Box;
import org._50dozenhits.dice_library.client.ui.Look.Text;
import org._50dozenhits.dice_library.hologram.HologramPanel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * DiceLib's own PanelUi: a HologramPanel's parts stacked in a box of a look, drawn with GuiGraphics. Holograms draw it
 * into a texture, mob panels onto the HUD. Laid out once at its width. Health parts refresh their numbers on tick.
 * Client only.
 */
public final class PanelRenderer implements PanelUi {
    private static final int ROW_GAP = 4;

    private final Look look;
    private final Box box;
    private final int width;
    private final List<Node> nodes = new ArrayList<>();
    private final List<HealthNode> health = new ArrayList<>();
    private final IntSupplier entity;
    private int height;

    /**
     * box is one of the look's (look.hologram(), look.mobPanel()). entity gives the id of the mob "health" reads
     */
    public PanelRenderer(HologramPanel panel, Look look, Box box, int width, Collection<String> disabled, IntSupplier entity) {
        this(panel, look, box, width, disabled, entity, null);
    }

    /**
     * with a header row above the parts: a 16 pixel icon (an item, or the mob's own portrait) and a title
     */
    public PanelRenderer(HologramPanel panel, Look look, Box box, int width, Collection<String> disabled, IntSupplier entity,
                         @Nullable Header header) {
        this.look = look;
        this.box = box;
        this.width = width;
        this.entity = entity;
        if (header != null) nodes.add(new HeaderNode(header));
        for (HologramPanel.Part part : panel.parts()) nodes.add(node(part, disabled));
        layout();
    }

    /**
     * icon is drawn unless empty. mobIcon draws the panel's mob instead
     */
    public record Header(Component title, ItemStack icon, boolean mobIcon) {
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    private void layout() {
        int inner = Math.max(1, width - box.padding() * 2);
        int y = box.padding();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) y += box.gap();
            y += nodes.get(i).layout(box.padding(), y, inner);
        }
        height = y + box.padding();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        box.background().draw(graphics, 0, 0, width, height);
        for (Node node : nodes) node.draw(graphics, mouseX, mouseY);
    }

    @Nullable
    @Override
    public String buttonAt(int mouseX, int mouseY) {
        for (Node node : nodes) {
            String id = node.buttonAt(mouseX, mouseY);
            if (id != null) return id;
        }
        return null;
    }

    /**
     * Updates live numbers (health). True when something shown changed.
     */
    @Override
    public boolean tick() {
        boolean changed = false;
        for (HealthNode node : health) changed |= node.refresh();
        return changed;
    }

    private Node node(HologramPanel.Part part, Collection<String> disabled) {
        return switch (part) {
            case HologramPanel.Title title -> new TextNode(title.text(), look.title(), true);
            case HologramPanel.Text text -> new TextNode(text.text(), look.text(), false);
            case HologramPanel.Button button -> new ButtonNode(button.id(), button.text(), disabled.contains(button.id()));
            case HologramPanel.Item item -> new ItemNode(item.item(), item.text().orElse(null));
            case HologramPanel.Bar bar -> new BarNode(bar.text().orElse(null), bar.fraction(), bar.color());
            case HologramPanel.Health healthPart -> {
                HealthNode node = new HealthNode(healthPart.text().orElse(Component.translatable("dicelib.ui.health")), healthPart.color());
                health.add(node);
                node.refresh();
                yield node;
            }
            case HologramPanel.Divider divider -> new DividerNode();
            case HologramPanel.Spacer spacer -> new SpacerNode(spacer.height());
            case HologramPanel.Row row -> {
                List<Node> inner = new ArrayList<>();
                for (HologramPanel.Part one : row.parts()) inner.add(node(one, disabled));
                yield new RowNode(inner);
            }
        };
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    static void text(GuiGraphics graphics, FormattedCharSequence line, int x, int y, Text style) {
        graphics.drawString(font(), line, x, y, style.color(), style.shadow());
    }

    static String number(float value) {
        return value == Math.floor(value) ? Integer.toString((int) value) : String.format("%.1f", value);
    }

    // One part, placed at x, y with a width. Layout returns its height.
    private abstract static class Node {
        int x, y, w, h;

        int layout(int x, int y, int width) {
            this.x = x;
            this.y = y;
            this.w = width;
            this.h = measure(width);
            return h;
        }

        abstract int measure(int width);

        abstract void draw(GuiGraphics graphics, int mouseX, int mouseY);

        @Nullable
        String buttonAt(int mouseX, int mouseY) {
            return null;
        }

        // moves it after layout (rows centre their parts)
        void shift(int dx, int dy) {
            x += dx;
            y += dy;
        }
    }

    private static final class TextNode extends Node {
        private final Component text;
        private final Text style;
        private final boolean centered;
        private List<FormattedCharSequence> lines = List.of();

        TextNode(Component text, Text style, boolean centered) {
            this.text = text;
            this.style = style;
            this.centered = centered;
        }

        @Override
        int measure(int width) {
            lines = font().split(text, Math.max(1, width));
            return lines.size() * font().lineHeight;
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            int line = y;
            for (FormattedCharSequence one : lines) {
                int at = centered ? x + (w - font().width(one)) / 2 : x;
                text(graphics, one, at, line, style);
                line += font().lineHeight;
            }
        }
    }

    private final class ButtonNode extends Node {
        private final String id;
        private final Component label;
        private final boolean off;
        private FormattedCharSequence shown = FormattedCharSequence.EMPTY;

        ButtonNode(String id, Component label, boolean off) {
            this.id = id;
            this.label = off ? label.copy().withStyle(ChatFormatting.DARK_GRAY) : label;
            this.off = off;
        }

        @Override
        int measure(int width) {
            List<FormattedCharSequence> lines = font().split(label, Math.max(1, width - 8));
            shown = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
            return look.buttonHeight();
        }

        private boolean hovered(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            Look.Buttons buttons = look.button();
            (off ? buttons.disabled() : hovered(mouseX, mouseY) ? buttons.hover() : buttons.normal()).draw(graphics, x, y, w, h);
            text(graphics, shown, x + (w - font().width(shown)) / 2, y + (h - 8) / 2, off ? buttons.disabledText() : buttons.text());
        }

        @Nullable
        @Override
        String buttonAt(int mouseX, int mouseY) {
            return hovered(mouseX, mouseY) ? id : null;
        }
    }

    private final class ItemNode extends Node {
        private final ItemStack item;
        @Nullable
        private final TextNode text;

        ItemNode(ItemStack item, @Nullable Component text) {
            this.item = item;
            this.text = text == null ? null : new TextNode(text, look.text(), false);
        }

        @Override
        int measure(int width) {
            if (text == null) return 16;
            return Math.max(16, text.measure(width - 20));
        }

        @Override
        int layout(int x, int y, int width) {
            super.layout(x, y, width);
            if (text != null) {
                int textHeight = text.layout(x + 20, y, width - 20);
                text.shift(0, (h - textHeight) / 2);
            }
            return h;
        }

        @Override
        void shift(int dx, int dy) {
            super.shift(dx, dy);
            if (text != null) text.shift(dx, dy);
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.renderItem(item, x, y + (h - 16) / 2);
            if (text != null) text.draw(graphics, mouseX, mouseY);
        }
    }

    // a fill over the look's bar background, one pixel in from its edge
    private void bar(GuiGraphics graphics, int x, int y, int w, float fraction, int color) {
        look.bar().draw(graphics, x, y, w, look.barHeight());
        int filled = Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
        if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + look.barHeight() - 1, color);
    }

    private final class BarNode extends Node {
        @Nullable
        private final TextNode label;
        private final float fraction;
        private final int color;

        BarNode(@Nullable Component label, float fraction, int color) {
            this.label = label == null ? null : new TextNode(label, look.dim(), false);
            this.fraction = fraction;
            this.color = color;
        }

        @Override
        int measure(int width) {
            return (label == null ? 0 : label.measure(width) + 1) + look.barHeight();
        }

        @Override
        int layout(int x, int y, int width) {
            super.layout(x, y, width);
            if (label != null) label.layout(x, y, width);
            return h;
        }

        @Override
        void shift(int dx, int dy) {
            super.shift(dx, dy);
            if (label != null) label.shift(dx, dy);
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            if (label != null) label.draw(graphics, mouseX, mouseY);
            bar(graphics, x, y + h - look.barHeight(), w, fraction, color);
        }
    }

    // the mob's health: its name for it and "current / max" over a bar
    private final class HealthNode extends Node {
        private final Component name;
        private final int color;
        private String value = "";
        private float fraction;

        HealthNode(Component name, int color) {
            this.name = name;
            this.color = color;
        }

        boolean refresh() {
            Minecraft mc = Minecraft.getInstance();
            Entity found = mc.level == null ? null : mc.level.getEntity(entity.getAsInt());
            if (!(found instanceof LivingEntity living)) return false;
            String now = number(living.getHealth()) + " / " + number(living.getMaxHealth());
            float fill = living.getMaxHealth() <= 0 ? 0 : living.getHealth() / living.getMaxHealth();
            boolean changed = !now.equals(value) || fill != fraction;
            value = now;
            fraction = fill;
            return changed;
        }

        @Override
        int measure(int width) {
            return font().lineHeight + 1 + look.barHeight();
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            int valueWidth = font().width(value);
            List<FormattedCharSequence> nameLines = font().split(name, Math.max(1, w - valueWidth - 4));
            if (!nameLines.isEmpty()) text(graphics, nameLines.get(0), x, y, look.dim());
            graphics.drawString(font(), value, x + w - valueWidth, y, look.dim().color(), look.dim().shadow());
            bar(graphics, x, y + font().lineHeight + 1, w, fraction, color);
        }
    }

    private final class DividerNode extends Node {
        @Override
        int measure(int width) {
            return 1;
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.fill(x, y, x + w, y + 1, look.divider());
        }
    }

    private static final class SpacerNode extends Node {
        private final int size;

        SpacerNode(int size) {
            this.size = size;
        }

        @Override
        int measure(int width) {
            return size;
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        }
    }

    // parts side by side, sharing the width evenly, centred on the tallest
    private static final class RowNode extends Node {
        private final List<Node> parts;

        RowNode(List<Node> parts) {
            this.parts = parts;
        }

        private int each(int width) {
            return parts.isEmpty() ? width : Math.max(1, (width - ROW_GAP * (parts.size() - 1)) / parts.size());
        }

        @Override
        int measure(int width) {
            int tallest = 0;
            for (Node part : parts) tallest = Math.max(tallest, part.measure(each(width)));
            return tallest;
        }

        @Override
        int layout(int x, int y, int width) {
            super.layout(x, y, width);
            int each = each(width), at = x;
            for (Node part : parts) {
                int partHeight = part.layout(at, y, each);
                part.shift(0, (h - partHeight) / 2);
                at += each + ROW_GAP;
            }
            return h;
        }

        @Override
        void shift(int dx, int dy) {
            super.shift(dx, dy);
            for (Node part : parts) part.shift(dx, dy);
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            for (Node part : parts) part.draw(graphics, mouseX, mouseY);
        }

        @Nullable
        @Override
        String buttonAt(int mouseX, int mouseY) {
            for (Node part : parts) {
                String id = part.buttonAt(mouseX, mouseY);
                if (id != null) return id;
            }
            return null;
        }
    }

    private final class HeaderNode extends Node {
        private final Header header;
        private final boolean icon;
        private final TextNode title;

        HeaderNode(Header header) {
            this.header = header;
            this.icon = header.mobIcon() || !header.icon().isEmpty();
            this.title = new TextNode(header.title(), look.title(), false);
        }

        @Override
        int measure(int width) {
            return Math.max(icon ? 16 : 0, title.measure(icon ? width - 20 : width));
        }

        @Override
        int layout(int x, int y, int width) {
            super.layout(x, y, width);
            int titleHeight = title.layout(icon ? x + 20 : x, y, icon ? width - 20 : width);
            title.shift(0, (h - titleHeight) / 2);
            return h;
        }

        @Override
        void shift(int dx, int dy) {
            super.shift(dx, dy);
            title.shift(dx, dy);
        }

        @Override
        void draw(GuiGraphics graphics, int mouseX, int mouseY) {
            if (icon) Portrait.draw(graphics, x, y + (h - 16) / 2, 16, header.mobIcon() ? ItemStack.EMPTY : header.icon(),
                    header.mobIcon() ? entity.getAsInt() : -1);
            title.draw(graphics, mouseX, mouseY);
        }
    }
}
