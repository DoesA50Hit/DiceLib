package org._50dozenhits.dice_library.hologram;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org._50dozenhits.dice_library.ui.UiActions;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What a hologram or a mob panel shows: a column of parts, top to bottom. Plain data that can be saved and sent.
 * Parts with a key (text, item, bar) can be changed one at a time later. In JSON each part is {"type": ..., ...}:
 *
 * <pre>{@code
 * {"type": "title", "text": "Ashen Crypt"}          {"type": "text", "key": "party", "text": "Party: 2/4"}
 * {"type": "button", "id": "start", "text": "Begin", "action": {"id": "dicelib:command", "command": "..."},
 *  "if": [{"id": "dicelib:flag", "flag": "leader"}]}
 * {"type": "item", "item": {"id": "minecraft:diamond"}, "text": "Reward"}
 * {"type": "bar", "key": "rage", "text": "Rage", "value": 20, "max": 100, "color": "#c03030"}
 * {"type": "health"}   {"type": "divider"}   {"type": "spacer", "height": 4}   {"type": "row", "parts": [...]}
 * }</pre>
 *
 * <p>"health" shows the health of the mob a panel or hologram is tied to, read on each client.
 */
public record HologramPanel(List<Part> parts) {
    public static final HologramPanel EMPTY = new HologramPanel(List.of());

    public sealed interface Part permits Title, Text, Button, Item, Bar, Health, Divider, Spacer, Row {
        String type();

        default String key() {
            return "";
        }
    }

    public record Title(Component text) implements Part {
        static final MapCodec<Title> CODEC = ComponentSerialization.CODEC.fieldOf("text").xmap(Title::new, Title::text);

        @Override
        public String type() {
            return "title";
        }
    }

    public record Text(String key, Component text) implements Part {
        static final MapCodec<Text> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("key", "").forGetter(Text::key),
                ComponentSerialization.CODEC.fieldOf("text").forGetter(Text::text)
        ).apply(i, Text::new));

        @Override
        public String type() {
            return "text";
        }
    }

    /**
     * A button runs its action (a UiActions call, which can be saved) or the Java handler the hologram holds for its
     * id. Its conditions are checked for each player: a player they fail sees it greyed out.
     */
    public record Button(String id, Component text, Optional<UiActions.Ref> action, List<UiActions.Ref> conditions) implements Part {
        static final MapCodec<Button> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Button::id),
                ComponentSerialization.CODEC.fieldOf("text").forGetter(Button::text),
                UiActions.Ref.CODEC.optionalFieldOf("action").forGetter(Button::action),
                UiActions.Ref.CODEC.listOf().optionalFieldOf("if", List.of()).forGetter(Button::conditions)
        ).apply(i, Button::new));

        @Override
        public String type() {
            return "button";
        }

        @Override
        public String key() {
            return id;
        }
    }

    public record Item(String key, ItemStack item, Optional<Component> text) implements Part {
        static final MapCodec<Item> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("key", "").forGetter(Item::key),
                ItemStack.CODEC.fieldOf("item").forGetter(Item::item),
                ComponentSerialization.CODEC.optionalFieldOf("text").forGetter(Item::text)
        ).apply(i, Item::new));

        @Override
        public String type() {
            return "item";
        }
    }

    public record Bar(String key, Optional<Component> text, float value, float max, int color) implements Part {
        static final MapCodec<Bar> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("key", "").forGetter(Bar::key),
                ComponentSerialization.CODEC.optionalFieldOf("text").forGetter(Bar::text),
                Codec.FLOAT.fieldOf("value").forGetter(Bar::value),
                Codec.FLOAT.optionalFieldOf("max", 1f).forGetter(Bar::max),
                COLOR.optionalFieldOf("color", 0xFF55CC55).forGetter(Bar::color)
        ).apply(i, Bar::new));

        public Bar {
            color = opaque(color);
        }

        public float fraction() {
            return max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
        }

        @Override
        public String type() {
            return "bar";
        }
    }

    /**
     * the tied mob's health as a bar with "current / max"
     */
    public record Health(Optional<Component> text, int color) implements Part {
        static final MapCodec<Health> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ComponentSerialization.CODEC.optionalFieldOf("text").forGetter(Health::text),
                COLOR.optionalFieldOf("color", 0xFFD83A3A).forGetter(Health::color)
        ).apply(i, Health::new));

        public Health {
            color = opaque(color);
        }

        @Override
        public String type() {
            return "health";
        }
    }

    public record Divider() implements Part {
        static final Divider INSTANCE = new Divider();
        static final MapCodec<Divider> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public String type() {
            return "divider";
        }
    }

    public record Spacer(int height) implements Part {
        static final MapCodec<Spacer> CODEC = Codec.INT.optionalFieldOf("height", 4).xmap(Spacer::new, Spacer::height);

        @Override
        public String type() {
            return "spacer";
        }
    }

    /**
     * parts side by side, sharing the width
     */
    public record Row(List<Part> parts) implements Part {
        static final MapCodec<Row> CODEC = Codec.lazyInitialized(() -> PART).listOf().fieldOf("parts").xmap(Row::new, Row::parts);

        @Override
        public String type() {
            return "row";
        }
    }

    /**
     * 0xRRGGBB reads as opaque, as the HUD bar colours do. A see-through colour needs its alpha (0xAARRGGBB)
     */
    public static int opaque(int color) {
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }

    /**
     * "#rrggbb", "#aarrggbb" or a number
     */
    public static final Codec<Integer> COLOR = Codec.either(Codec.INT, Codec.STRING).comapFlatMap(either -> either.map(DataResult::success, text -> {
        try {
            String hex = text.startsWith("#") ? text.substring(1) : text;
            long value = Long.parseLong(hex, 16);
            return DataResult.success((int) (hex.length() <= 6 ? value | 0xFF000000L : value));
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "Not a colour: " + text);
        }
    }), color -> com.mojang.datafixers.util.Either.right(String.format("#%08x", color)));

    public static final Codec<Part> PART = Codec.STRING.<Part>dispatch("type", Part::type, HologramPanel::codecOf);

    private static MapCodec<? extends Part> codecOf(String type) {
        return switch (type) {
            case "title" -> Title.CODEC;
            case "text" -> Text.CODEC;
            case "button" -> Button.CODEC;
            case "item" -> Item.CODEC;
            case "bar" -> Bar.CODEC;
            case "health" -> Health.CODEC;
            case "divider" -> Divider.CODEC;
            case "spacer" -> Spacer.CODEC;
            case "row" -> Row.CODEC;
            default -> throw new IllegalArgumentException("Unknown hologram part " + type);
        };
    }

    public static final Codec<HologramPanel> CODEC = PART.listOf().xmap(HologramPanel::new, HologramPanel::parts);
    @ApiStatus.Internal
    public static final StreamCodec<RegistryFriendlyByteBuf, HologramPanel> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

    // ---- looking parts up ----

    /**
     * every button, rows included
     */
    public List<Button> buttons() {
        List<Button> out = new ArrayList<>();
        collect(parts, out);
        return out;
    }

    private static void collect(List<Part> parts, List<Button> out) {
        for (Part part : parts) {
            if (part instanceof Button button) out.add(button);
            else if (part instanceof Row row) collect(row.parts(), out);
        }
    }

    @Nullable
    public Button button(String id) {
        for (Button button : buttons()) if (button.id().equals(id)) return button;
        return null;
    }

    /**
     * A copy with the keyed part swapped for another. The same panel when no part has the key
     */
    public HologramPanel with(String key, Part replacement) {
        return new HologramPanel(replace(parts, key, replacement));
    }

    private static List<Part> replace(List<Part> parts, String key, Part replacement) {
        List<Part> out = new ArrayList<>(parts.size());
        for (Part part : parts) {
            if (!key.isEmpty() && key.equals(part.key())) out.add(replacement);
            else if (part instanceof Row row) out.add(new Row(replace(row.parts(), key, replacement)));
            else out.add(part);
        }
        return List.copyOf(out);
    }

    @Nullable
    public Part find(String key) {
        return find(parts, key);
    }

    @Nullable
    private static Part find(List<Part> parts, String key) {
        for (Part part : parts) {
            if (key.equals(part.key())) return part;
            if (part instanceof Row row) {
                Part inside = find(row.parts(), key);
                if (inside != null) return inside;
            }
        }
        return null;
    }

    // ---- building ----

    @FunctionalInterface
    public interface Handler {
        void click(ServerPlayer player, Hologram hologram);
    }

    public static Built build(Consumer<Builder> content) {
        Builder builder = new Builder();
        content.accept(builder);
        return new Built(new HologramPanel(List.copyOf(builder.parts)), Map.copyOf(builder.handlers));
    }

    public record Built(HologramPanel panel, Map<String, Handler> handlers) {
    }

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();
        private final Map<String, Handler> handlers = new HashMap<>();

        public Builder title(Component text) {
            parts.add(new Title(text));
            return this;
        }

        public Builder text(Component text) {
            return text("", text);
        }

        public Builder text(String key, Component text) {
            parts.add(new Text(key, text));
            return this;
        }

        /**
         * runs Java code. A hologram with one can't be saved
         */
        public Builder button(String id, Component text, Handler handler) {
            return button(id, text, handler, List.of());
        }

        public Builder button(String id, Component text, Handler handler, List<UiActions.Ref> conditions) {
            parts.add(new Button(id, text, Optional.empty(), List.copyOf(conditions)));
            handlers.put(id, handler);
            return this;
        }

        /**
         * runs a UiActions call. Can be saved
         */
        public Builder button(String id, Component text, UiActions.Ref action) {
            return button(id, text, action, List.of());
        }

        public Builder button(String id, Component text, UiActions.Ref action, List<UiActions.Ref> conditions) {
            parts.add(new Button(id, text, Optional.of(action), List.copyOf(conditions)));
            return this;
        }

        public Builder item(ItemStack item, @Nullable Component text) {
            return item("", item, text);
        }

        public Builder item(String key, ItemStack item, @Nullable Component text) {
            parts.add(new Item(key, item.copy(), Optional.ofNullable(text)));
            return this;
        }

        public Builder bar(String key, @Nullable Component text, float value, float max, int color) {
            parts.add(new Bar(key, Optional.ofNullable(text), value, max, color));
            return this;
        }

        public Builder health() {
            parts.add(new Health(Optional.empty(), 0xFFD83A3A));
            return this;
        }

        public Builder divider() {
            parts.add(Divider.INSTANCE);
            return this;
        }

        public Builder spacer(int height) {
            parts.add(new Spacer(height));
            return this;
        }

        /**
         * parts side by side
         */
        public Builder row(Consumer<Builder> row) {
            Builder inner = new Builder();
            row.accept(inner);
            parts.add(new Row(List.copyOf(inner.parts)));
            handlers.putAll(inner.handlers);
            return this;
        }

        public Builder add(Part part) {
            parts.add(part);
            return this;
        }

        /**
         * the parts so far, without the handlers
         */
        public HologramPanel toPanel() {
            return new HologramPanel(List.copyOf(parts));
        }
    }
}
