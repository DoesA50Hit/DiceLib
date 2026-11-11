package org._50dozenhits.dice_library.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.dialogue.Dialogues;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Actions and conditions that data can call by id: a dialogue choice, a hologram button, an audience. Java registers
 * them once at startup. JSON names them with an "id" and passes its other keys as arguments:
 *
 * <pre>{@code
 * UiActions.action(MyMod.id("repair_held"), (player, context, args) -> repair(player.getMainHandItem()));
 * {"id": "mymod:repair_held"}      {"id": "dicelib:give", "item": "minecraft:bread", "count": 3}
 * }</pre>
 *
 * <p>The built-in ones are registered by init(). Server side.
 */
public final class UiActions {
    @FunctionalInterface
    public interface Action {
        void run(ServerPlayer player, Context context, JsonObject args);
    }

    @FunctionalInterface
    public interface Condition {
        boolean test(ServerPlayer player, Context context, JsonObject args);
    }

    /**
     * where a call comes from: the level, the speaker or hologram's entity, and the dialogue or hologram it belongs to
     */
    public record Context(ServerLevel level, @Nullable Entity speaker, @Nullable ResourceLocation dialogue, @Nullable String node,
                          @Nullable UUID hologram) {
        public static Context of(ServerPlayer player) {
            return new Context(player.serverLevel(), null, null, null, null);
        }
    }

    /**
     * One call: an id and its arguments. In JSON either an id string or an object with "id" and the arguments.
     */
    public record Ref(ResourceLocation id, JsonObject args) {
        public static final Codec<Ref> CODEC = ExtraCodecs.JSON.comapFlatMap(Ref::parse,
                ref -> {
                    JsonObject json = ref.args.deepCopy();
                    json.addProperty("id", ref.id.toString());
                    return json;
                });

        public static DataResult<Ref> parse(JsonElement json) {
            try {
                if (json.isJsonPrimitive()) return DataResult.success(new Ref(ResourceLocation.parse(json.getAsString()), new JsonObject()));
                JsonObject object = json.getAsJsonObject().deepCopy();
                ResourceLocation id = ResourceLocation.parse(object.remove("id").getAsString());
                return DataResult.success(new Ref(id, object));
            } catch (RuntimeException e) {
                return DataResult.error(() -> "Not an action or condition: " + json);
            }
        }

        public static Ref read(JsonElement json) {
            return parse(json).getOrThrow(IllegalArgumentException::new);
        }
    }

    private static final Map<ResourceLocation, Action> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Condition> CONDITIONS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    public static void action(ResourceLocation id, Action action) {
        ACTIONS.put(id, action);
    }

    public static void condition(ResourceLocation id, Condition condition) {
        CONDITIONS.put(id, condition);
    }

    public static boolean hasAction(ResourceLocation id) {
        return ACTIONS.containsKey(id);
    }

    public static boolean hasCondition(ResourceLocation id) {
        return CONDITIONS.containsKey(id);
    }

    public static Ref ref(String id, JsonObject args) {
        return new Ref(ResourceLocation.parse(id), args);
    }

    /**
     * Runs it. An unknown id does nothing and is logged once. So is one that throws (an argument its JSON left out).
     */
    public static void run(Ref ref, ServerPlayer player, Context context) {
        Action action = ACTIONS.get(ref.id());
        if (action == null) {
            if (WARNED.add(ref.id())) DiceLib.LOGGER.warn("Unknown UI action {}", ref.id());
            return;
        }
        try {
            action.run(player, context, ref.args());
        } catch (RuntimeException e) {
            if (WARNED.add(ref.id())) DiceLib.LOGGER.warn("UI action {} failed with arguments {}", ref.id(), ref.args(), e);
        }
    }

    public static void runAll(List<Ref> refs, ServerPlayer player, Context context) {
        for (Ref ref : refs) run(ref, player, context);
    }

    /**
     * False for an unknown id, and for one that throws. Either is logged once.
     */
    public static boolean test(Ref ref, ServerPlayer player, Context context) {
        Condition condition = CONDITIONS.get(ref.id());
        if (condition == null) {
            if (WARNED.add(ref.id())) DiceLib.LOGGER.warn("Unknown UI condition {}", ref.id());
            return false;
        }
        try {
            return condition.test(player, context, ref.args());
        } catch (RuntimeException e) {
            if (WARNED.add(ref.id())) DiceLib.LOGGER.warn("UI condition {} failed with arguments {}", ref.id(), ref.args(), e);
            return false;
        }
    }

    public static boolean testAll(List<Ref> refs, ServerPlayer player, Context context) {
        for (Ref ref : refs) {
            if (!test(ref, player, context)) return false;
        }
        return true;
    }

    // ---- flags: names a player has been marked with, saved and kept through death ----

    public static boolean flagged(ServerPlayer player, String flag) {
        return player.getData(org._50dozenhits.dice_library.registry.DiceAttachments.UI_FLAGS).contains(flag);
    }

    public static void setFlag(ServerPlayer player, String flag, boolean on) {
        Set<String> flags = new HashSet<>(player.getData(org._50dozenhits.dice_library.registry.DiceAttachments.UI_FLAGS));
        if (on ? flags.add(flag) : flags.remove(flag)) player.setData(org._50dozenhits.dice_library.registry.DiceAttachments.UI_FLAGS, Set.copyOf(flags));
    }

    // ---- built-ins ----

    private static Item item(JsonObject args) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.get("item").getAsString()));
    }

    private static int count(JsonObject args) {
        return args.has("count") ? args.get("count").getAsInt() : 1;
    }

    private static int held(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Registers the built-in actions and conditions. Called once from the mod constructor.
     */
    @ApiStatus.Internal
    public static void init() {
        // Runs a command as the player, so @s is them, with operator rights (permission level 2) unless "permission"
        // gives another level from 0 to 4. Anyone who can press the button or pick the choice runs it at that level,
        // so keep the command fixed and give it no more rights than it needs.
        action(DiceLib.id("command"), (player, context, args) -> {
            int level = args.has("permission") ? Math.clamp(args.get("permission").getAsInt(), 0, 4) : 2;
            player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(level).withSuppressedOutput(),
                    args.get("command").getAsString());
        });
        action(DiceLib.id("give"), (player, context, args) -> {
            ItemStack stack = new ItemStack(item(args), count(args));
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        });
        action(DiceLib.id("take"), (player, context, args) -> {
            Item item = item(args);
            int left = count(args);
            for (ItemStack stack : player.getInventory().items) {
                if (left <= 0) break;
                if (!stack.is(item)) continue;
                int taken = Math.min(left, stack.getCount());
                stack.shrink(taken);
                left -= taken;
            }
        });
        action(DiceLib.id("flag"), (player, context, args) -> setFlag(player, args.get("flag").getAsString(), true));
        action(DiceLib.id("unflag"), (player, context, args) -> setFlag(player, args.get("flag").getAsString(), false));
        action(DiceLib.id("sound"), (player, context, args) -> {
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(args.get("sound").getAsString()));
            if (sound == null) return;
            float volume = args.has("volume") ? args.get("volume").getAsFloat() : 1;
            float pitch = args.has("pitch") ? args.get("pitch").getAsFloat() : 1;
            player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch);
        });
        // starts another dialogue tree, with the same speaker
        action(DiceLib.id("dialogue"), (player, context, args) -> Dialogues.start(player, ResourceLocation.parse(args.get("dialogue").getAsString()),
                context.speaker(), args.has("node") ? args.get("node").getAsString() : null));
        // ends the player's dialogue
        action(DiceLib.id("close"), (player, context, args) -> Dialogues.end(player));

        condition(DiceLib.id("has_item"), (player, context, args) -> held(player, item(args)) >= count(args));
        condition(DiceLib.id("flag"), (player, context, args) -> flagged(player, args.get("flag").getAsString()));
        condition(DiceLib.id("not"), (player, context, args) -> !test(Ref.read(args.get("condition")), player, context));
        condition(DiceLib.id("permission"), (player, context, args) -> player.hasPermissions(args.has("level") ? args.get("level").getAsInt() : 2));
    }
}
