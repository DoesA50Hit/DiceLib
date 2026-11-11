package org._50dozenhits.dice_library.ui;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who a line, a hologram or a mob panel is for: everyone, named players, a team, a scoreboard tag, a permission level,
 * a registered condition, or any mix of those. Every kind but of(predicate) can be saved and written in JSON:
 *
 * <pre>{@code
 * {"type": "tagged", "tag": "party"}     {"type": "any_of", "of": [{"type": "team", "team": "red"}, ...]}
 * }</pre>
 *
 * <p>Distance is never part of an audience. Whatever uses one adds its own range.
 */
public sealed interface Audience {
    boolean test(ServerPlayer player, UiActions.Context context);

    // false for of(predicate), which lives in memory only
    default boolean saveable() {
        return true;
    }

    String type();

    static Audience everyone() {
        return Everyone.INSTANCE;
    }

    static Audience players(UUID... players) {
        return new Players(List.of(players));
    }

    static Audience players(ServerPlayer... players) {
        return new Players(Arrays.stream(players).map(ServerPlayer::getUUID).toList());
    }

    static Audience players(List<UUID> players) {
        return new Players(List.copyOf(players));
    }

    static Audience team(String team) {
        return new Team(team);
    }

    // players carrying this scoreboard tag (/tag @s add party)
    static Audience tagged(String tag) {
        return new Tagged(tag);
    }

    // players with at least this permission level (2 for operators)
    static Audience permission(int level) {
        return new Permission(level);
    }

    // players a registered UiActions condition passes
    static Audience condition(ResourceLocation id, JsonObject args) {
        return new Condition(new UiActions.Ref(id, args));
    }

    static Audience allOf(Audience... parts) {
        return new AllOf(List.of(parts));
    }

    static Audience anyOf(Audience... parts) {
        return new AnyOf(List.of(parts));
    }

    static Audience not(Audience part) {
        return new Not(part);
    }

    // anything else. Can't be saved, so not for a saved hologram
    static Audience of(Predicate<ServerPlayer> predicate) {
        return new OfPredicate(predicate);
    }

    record Everyone() implements Audience {
        static final Everyone INSTANCE = new Everyone();
        static final MapCodec<Everyone> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            return true;
        }

        @Override
        public String type() {
            return "everyone";
        }
    }

    record Players(List<UUID> players) implements Audience {
        static final MapCodec<Players> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.STRING_CODEC.listOf().fieldOf("players").forGetter(Players::players)
        ).apply(i, Players::new));

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            return players.contains(player.getUUID());
        }

        @Override
        public String type() {
            return "players";
        }
    }

    record Team(String team) implements Audience {
        static final MapCodec<Team> CODEC = Codec.STRING.fieldOf("team").xmap(Team::new, Team::team);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            PlayerTeam on = player.getScoreboard().getPlayersTeam(player.getScoreboardName());
            return on != null && on.getName().equals(team);
        }

        @Override
        public String type() {
            return "team";
        }
    }

    record Tagged(String tag) implements Audience {
        static final MapCodec<Tagged> CODEC = Codec.STRING.fieldOf("tag").xmap(Tagged::new, Tagged::tag);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            Set<String> tags = player.getTags();
            return tags.contains(tag);
        }

        @Override
        public String type() {
            return "tagged";
        }
    }

    record Permission(int level) implements Audience {
        static final MapCodec<Permission> CODEC = Codec.INT.fieldOf("level").xmap(Permission::new, Permission::level);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            return player.hasPermissions(level);
        }

        @Override
        public String type() {
            return "permission";
        }
    }

    record Condition(UiActions.Ref condition) implements Audience {
        static final MapCodec<Condition> CODEC = UiActions.Ref.CODEC.fieldOf("condition").xmap(Condition::new, Condition::condition);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            return UiActions.test(condition, player, context);
        }

        @Override
        public String type() {
            return "condition";
        }
    }

    record AllOf(List<Audience> of) implements Audience {
        static final MapCodec<AllOf> CODEC = Codec.lazyInitialized(() -> Audience.CODEC).listOf().fieldOf("of").xmap(AllOf::new, AllOf::of);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            for (Audience part : of) {
                if (!part.test(player, context)) return false;
            }
            return true;
        }

        @Override
        public boolean saveable() {
            return of.stream().allMatch(Audience::saveable);
        }

        @Override
        public String type() {
            return "all_of";
        }
    }

    record AnyOf(List<Audience> of) implements Audience {
        static final MapCodec<AnyOf> CODEC = Codec.lazyInitialized(() -> Audience.CODEC).listOf().fieldOf("of").xmap(AnyOf::new, AnyOf::of);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            for (Audience part : of) {
                if (part.test(player, context)) return true;
            }
            return false;
        }

        @Override
        public boolean saveable() {
            return of.stream().allMatch(Audience::saveable);
        }

        @Override
        public String type() {
            return "any_of";
        }
    }

    record Not(Audience of) implements Audience {
        static final MapCodec<Not> CODEC = Codec.lazyInitialized(() -> Audience.CODEC).fieldOf("of").xmap(Not::new, Not::of);

        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            return !of.test(player, context);
        }

        @Override
        public boolean saveable() {
            return of.saveable();
        }

        @Override
        public String type() {
            return "not";
        }
    }

    record OfPredicate(Predicate<ServerPlayer> predicate) implements Audience {
        @Override
        public boolean test(ServerPlayer player, UiActions.Context context) {
            return predicate.test(player);
        }

        @Override
        public boolean saveable() {
            return false;
        }

        @Override
        public String type() {
            return "predicate";
        }
    }

    // by "type". Encoding of(predicate) fails
    Codec<Audience> CODEC = Codec.STRING.<Audience>dispatch("type", Audience::type, Audience::codecOf)
            .validate(a -> a.saveable() ? DataResult.success(a) : DataResult.error(() -> "An audience made with Audience.of(predicate) can't be saved"));

    private static MapCodec<? extends Audience> codecOf(String type) {
        return switch (type) {
            case "everyone" -> Everyone.CODEC;
            case "players" -> Players.CODEC;
            case "team" -> Team.CODEC;
            case "tagged" -> Tagged.CODEC;
            case "permission" -> Permission.CODEC;
            case "condition" -> Condition.CODEC;
            case "all_of" -> AllOf.CODEC;
            case "any_of" -> AnyOf.CODEC;
            case "not" -> Not.CODEC;
            default -> throw new IllegalArgumentException("Unknown audience type " + type);
        };
    }
}
