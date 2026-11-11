package org._50dozenhits.dice_library.dialogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org._50dozenhits.dice_library.ui.UiActions;
import org._50dozenhits.dice_library.ui.UiActions.Ref;
import org._50dozenhits.dice_library.ui.UiLooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * One conversation, read from {@code data/<namespace>/dicelib/dialogues/<path>.json}. Its id is {@code <namespace>:<path>}. The file:
 *
 * <pre>{@code
 * speaker   the name shown. The speaker entity's own name when left out
 * portrait  "speaker" (the speaker's model), an item id, or left out for none
 * voice     a sound played softly as the text types (a sound id), and pitch for it (1)
 * style     a look or custom UI id (UiLooks), dicelib:default by default
 * range     blocks the player may wander from the speaker before it ends (8)
 * overhear  blocks around the speaker in which other players see the lines as subtitles (0 = nobody)
 * start     the first node ("start")
 * nodes     node name -> {lines, actions, choices, next, end, close}
 * }</pre>
 *
 * <p>A node shows its lines one after another, runs its actions when entered, then offers its choices or moves on to
 * next (or ends, with "end": true). "close": false stops Escape from closing it. A choice has text, next or end, "if"
 * (conditions that must all pass, or it is hidden. "locked": true shows it greyed out instead) and actions. A choice
 * with neither next nor end stays on the node.
 *
 * <p>Text is a vanilla text component: a plain string, or an object such as {"translate": "..."}. Placeholders in braces
 * are filled in when a line is sent: {player} and {speaker} become the names, and Dialogues.placeholder adds more. They
 * work in plain strings, in "text" objects and in a translation's "with" arguments, so a translated line puts "%s" in
 * its lang file and the placeholder in "with": {"translate": "dialogue.mymod.hello", "with": ["{player}"]}.
 * A line can be {"speaker": "...", "line": ...} to give it another speaker.
 */
public record DialogueTree(ResourceLocation id, @Nullable Text speaker, String portrait, @Nullable ResourceLocation voice, float pitch,
                           ResourceLocation style, double range, double overhear, String start, Map<String, Node> nodes) {
    public static final double DEFAULT_RANGE = 8;

    /**
     * a plain string (placeholders filled in when sent) or a component
     */
    public record Text(@Nullable String plain, @Nullable Component component) {
        public static Text of(String plain) {
            return new Text(plain, null);
        }

        public static Text of(Component component) {
            return new Text(null, component);
        }

        /**
         * with only {player} and {speaker}
         */
        public Component resolve(Component player, Component speaker) {
            return resolve(name -> switch (name) {
                case "player" -> player;
                case "speaker" -> speaker;
                default -> null;
            });
        }

        /**
         * {@code values} gives each placeholder's text by name, or null to leave it as written
         */
        public Component resolve(Function<String, @Nullable Component> values) {
            return component != null ? fill(component, values) : fill(plain == null ? "" : plain, values);
        }

        /**
         * The component with its placeholders filled: in its own text, in a translation's arguments and in every
         * sibling, keeping each part's style.
         */
        public static Component fill(Component component, Function<String, @Nullable Component> values) {
            MutableComponent out;
            if (component.getContents() instanceof PlainTextContents text) {
                out = fill(text.text(), values);
            } else if (component.getContents() instanceof TranslatableContents translatable) {
                Object[] args = translatable.getArgs().clone();
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Component arg) args[i] = fill(arg, values);
                    else if (args[i] instanceof String arg && arg.indexOf('{') >= 0) args[i] = fill(arg, values);
                }
                out = Component.translatableWithFallback(translatable.getKey(), translatable.getFallback(), args);
            } else {
                out = MutableComponent.create(component.getContents());
            }
            out.setStyle(component.getStyle());
            for (Component sibling : component.getSiblings()) out.append(fill(sibling, values));
            return out;
        }

        /**
         * A plain string with its placeholders filled. A name nobody knows stays as written, braces and all.
         */
        public static MutableComponent fill(String text, Function<String, @Nullable Component> values) {
            if (text.indexOf('{') < 0) return Component.literal(text);
            MutableComponent out = Component.empty();
            StringBuilder plain = new StringBuilder();
            int at = 0;
            while (at < text.length()) {
                int open = text.indexOf('{', at);
                int close = open < 0 ? -1 : text.indexOf('}', open + 1);
                if (close < 0) break;
                String name = text.substring(open + 1, close);
                Component value = PLACEHOLDER_NAME.matcher(name).matches() ? values.apply(name) : null;
                if (value == null) {
                    plain.append(text, at, open + 1);
                    at = open + 1;
                    continue;
                }
                plain.append(text, at, open);
                if (!plain.isEmpty()) out.append(plain.toString());
                plain.setLength(0);
                out.append(value);
                at = close + 1;
            }
            plain.append(text.substring(at));
            if (!plain.isEmpty()) out.append(plain.toString());
            return out;
        }
    }

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[a-z0-9_.:-]+");

    public record Line(Text text, @Nullable Text speaker) {
    }

    public record Choice(Text text, @Nullable String next, boolean end, List<Ref> conditions, boolean locked, List<Ref> actions) {
    }

    public record Node(String id, List<Line> lines, List<Ref> actions, List<Choice> choices, @Nullable String next, boolean end,
                       boolean canClose) {
    }

    /**
     * The item a portrait names, or empty for "speaker" and none
     */
    public ItemStack portraitItem() {
        if (portrait.isEmpty() || portrait.equals("speaker")) return ItemStack.EMPTY;
        ResourceLocation item = ResourceLocation.tryParse(portrait);
        return item == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(item));
    }

    public boolean speakerPortrait() {
        return portrait.equals("speaker");
    }

    @Nullable
    public Node node(String name) {
        return nodes.get(name);
    }

    // ---- reading ----

    /**
     * What is wrong with a file. A tree with problems still loads when it can be used
     */
    public record Parsed(@Nullable DialogueTree tree, List<String> problems) {
    }

    public static Parsed parse(ResourceLocation id, JsonElement json) {
        List<String> problems = new ArrayList<>();
        if (!json.isJsonObject()) return new Parsed(null, List.of("The file must be a JSON object"));
        JsonObject root = json.getAsJsonObject();
        try {
            Text speaker = root.has("speaker") ? text(root.get("speaker"), problems, "speaker") : null;
            String portrait = root.has("portrait") ? root.get("portrait").getAsString() : "";
            if (!portrait.isEmpty() && !portrait.equals("speaker")) {
                ResourceLocation item = ResourceLocation.tryParse(portrait);
                if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) problems.add("portrait: no item " + portrait);
            }
            ResourceLocation voice = root.has("voice") ? ResourceLocation.parse(root.get("voice").getAsString()) : null;
            if (voice != null && !BuiltInRegistries.SOUND_EVENT.containsKey(voice)) problems.add("voice: no sound " + voice);
            float pitch = root.has("pitch") ? root.get("pitch").getAsFloat() : 1;
            ResourceLocation style = root.has("style") ? ResourceLocation.parse(root.get("style").getAsString()) : UiLooks.DEFAULT;
            double range = root.has("range") ? root.get("range").getAsDouble() : DEFAULT_RANGE;
            double overhear = root.has("overhear") ? root.get("overhear").getAsDouble() : 0;
            String start = root.has("start") ? root.get("start").getAsString() : "start";
            if (!root.has("nodes") || !root.get("nodes").isJsonObject()) return new Parsed(null, List.of("nodes: missing, or not an object"));

            Map<String, Node> nodes = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("nodes").entrySet()) {
                String where = "nodes." + entry.getKey();
                if (!entry.getValue().isJsonObject()) {
                    problems.add(where + ": not an object");
                    continue;
                }
                nodes.put(entry.getKey(), node(entry.getKey(), entry.getValue().getAsJsonObject(), problems, where));
            }
            if (!nodes.containsKey(start)) {
                problems.add("start: no node named " + start);
                return new Parsed(null, problems);
            }
            for (Node node : nodes.values()) {
                if (node.next() != null && !nodes.containsKey(node.next())) problems.add("nodes." + node.id() + ".next: no node named " + node.next());
                for (int i = 0; i < node.choices().size(); i++) {
                    String next = node.choices().get(i).next();
                    if (next != null && !nodes.containsKey(next)) problems.add("nodes." + node.id() + ".choices[" + i + "].next: no node named " + next);
                }
            }
            return new Parsed(new DialogueTree(id, speaker, portrait, voice, pitch, style, range, overhear, start, Map.copyOf(nodes)), problems);
        } catch (RuntimeException e) {
            problems.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Parsed(null, problems);
        }
    }

    private static Node node(String name, JsonObject json, List<String> problems, String where) {
        List<Line> lines = new ArrayList<>();
        if (json.has("lines")) {
            JsonArray array = json.getAsJsonArray("lines");
            for (int i = 0; i < array.size(); i++) lines.add(line(array.get(i), problems, where + ".lines[" + i + "]"));
        }
        List<Ref> actions = refs(json, "actions", problems, where, UiActions::hasAction, "action");
        List<Choice> choices = new ArrayList<>();
        if (json.has("choices")) {
            JsonArray array = json.getAsJsonArray("choices");
            for (int i = 0; i < array.size(); i++) {
                String at = where + ".choices[" + i + "]";
                JsonObject choice = array.get(i).getAsJsonObject();
                Text text = text(choice.get("text"), problems, at + ".text");
                String next = choice.has("next") ? choice.get("next").getAsString() : null;
                boolean end = choice.has("end") && choice.get("end").getAsBoolean();
                List<Ref> conditions = refs(choice, "if", problems, at, UiActions::hasCondition, "condition");
                boolean locked = choice.has("locked") && choice.get("locked").getAsBoolean();
                choices.add(new Choice(text, next, end, conditions, locked, refs(choice, "actions", problems, at, UiActions::hasAction, "action")));
            }
        }
        String next = json.has("next") ? json.get("next").getAsString() : null;
        boolean end = json.has("end") && json.get("end").getAsBoolean();
        boolean canClose = !json.has("close") || json.get("close").getAsBoolean();
        return new Node(name, List.copyOf(lines), actions, List.copyOf(choices), next, end, canClose);
    }

    private static Line line(JsonElement json, List<String> problems, String where) {
        if (json.isJsonObject() && json.getAsJsonObject().has("line")) {
            JsonObject object = json.getAsJsonObject();
            Text speaker = object.has("speaker") ? text(object.get("speaker"), problems, where + ".speaker") : null;
            return new Line(text(object.get("line"), problems, where + ".line"), speaker);
        }
        return new Line(text(json, problems, where), null);
    }

    static Text text(@Nullable JsonElement json, List<String> problems, String where) {
        if (json == null) {
            problems.add(where + ": missing");
            return Text.of("");
        }
        if (json.isJsonPrimitive()) return Text.of(json.getAsString());
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(error -> problems.add(where + ": " + error))
                .map(Text::of).orElse(Text.of(""));
    }

    private static List<Ref> refs(JsonObject json, String key, List<String> problems, String where,
                                  Function<ResourceLocation, Boolean> known, String kind) {
        if (!json.has(key)) return List.of();
        JsonArray array = json.get(key).isJsonArray() ? json.getAsJsonArray(key) : wrap(json.get(key));
        List<Ref> refs = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String at = where + "." + key + "[" + i + "]";
            Ref.parse(array.get(i)).resultOrPartial(error -> problems.add(at + ": " + error)).ifPresent(ref -> {
                if (!known.apply(ref.id())) problems.add(at + ": no " + kind + " " + ref.id());
                refs.add(ref);
            });
        }
        return List.copyOf(refs);
    }

    private static JsonArray wrap(JsonElement one) {
        JsonArray array = new JsonArray();
        array.add(one);
        return array;
    }
}
