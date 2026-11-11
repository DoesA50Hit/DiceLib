package org._50dozenhits.dice_library.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Text that types itself out. Wrap the whole line once, then reveal it a character at a time, so words never jump to
 * the next line as they appear. Client only.
 */
public final class TypedText {
    /**
     * How many characters a component shows
     */
    public static int length(Component text) {
        return text.getString().length();
    }

    /**
     * The first {@code count} characters of a component, styles kept
     */
    public static Component typed(Component text, int count) {
        if (count >= length(text)) return text;
        return cut(text, count);
    }

    /**
     * the whole text split to fit {@code width}
     */
    public static List<FormattedText> wrap(Font font, Component text, int width) {
        return font.getSplitter().splitLines(text, Math.max(1, width), Style.EMPTY);
    }

    /**
     * the wrapped lines with only the first {@code count} characters showing, ready to draw
     */
    public static List<FormattedCharSequence> reveal(List<FormattedText> lines, int count) {
        List<FormattedCharSequence> out = new ArrayList<>(lines.size());
        int left = count;
        for (FormattedText line : lines) {
            int length = line.getString().length();
            FormattedText shown = left >= length ? line : cut(line, Math.max(0, left));
            out.add(Language.getInstance().getVisualOrder(shown));
            left -= length;
        }
        return out;
    }

    /**
     * the widest of the lines
     */
    public static int width(Font font, List<FormattedText> lines) {
        int widest = 0;
        for (FormattedText line : lines) widest = Math.max(widest, font.width(line));
        return widest;
    }

    private static MutableComponent cut(FormattedText text, int count) {
        MutableComponent out = Component.empty();
        int[] left = {count};
        text.visit((style, part) -> {
            if (left[0] <= 0) return FormattedText.STOP_ITERATION;
            String piece = part.length() > left[0] ? part.substring(0, left[0]) : part;
            left[0] -= piece.length();
            out.append(Component.literal(piece).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private TypedText() {
    }
}
