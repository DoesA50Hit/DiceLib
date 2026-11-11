package org._50dozenhits.dice_library.client.render.bodypart;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The body parts there are (BodyPart), which group wears which (a group is any id, such as a race), and the source that
 * says what each player wears right now and in what look. BodyPartLayer draws what the source returns. Register parts
 * and set the source at client setup (FMLClientSetupEvent). Client only.
 *
 * <pre>{@code
 * BodyParts.register(BodyPart.builder(MyMod.id("horns")).anchor(28, 0).fidgets("fidget_tilt").build());
 * BodyParts.add(MyMod.id("demon"), BodyParts.use(MyMod.id("horns")));
 * BodyParts.setSource(player -> MyRaces.of(player) == null ? List.of() : BodyParts.wear(BodyParts.of(MyRaces.of(player)),
 *         new BodyParts.Look("crimson", false, "none", false, "curled", 1)));
 * }</pre>
 *
 * <p>A look names textures and bones of the part: color picks {@code <part>_<color>.png}, markings (or "none") the overlay
 * {@code mark_<markings>.png}, shape the bone group to show, and a rare color or marking adds a glow pass.
 */
public final class BodyParts {
    // client setup runs side by side for every mod
    private static final Map<ResourceLocation, BodyPart> PARTS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, List<Use>> USES = new ConcurrentHashMap<>();
    private static Function<ResourceLocation, @Nullable ResourceLocation> parent = id -> null;
    private static Function<Player, List<Worn>> source = player -> List.of();

    /**
     * One part on one group. Count is passed to the part's BoneHook (how many tails show).
     */
    public record Use(ResourceLocation part, int count) {
    }

    /**
     * How a worn part is drawn.
     */
    public record Look(String color, boolean rareColor, String markings, boolean rareMarkings, String shape, float scale) {
        public static final Look PLAIN = new Look("default", false, "none", false, "", 1);
    }

    /**
     * One part a player wears, in its look.
     */
    public record Worn(Use use, Look look) {
    }

    public static Use use(ResourceLocation part) {
        return new Use(part, 0);
    }

    public static Use use(ResourceLocation part, int count) {
        return new Use(part, count);
    }

    public static void register(BodyPart part) {
        PARTS.put(part.id(), part);
    }

    @Nullable
    public static BodyPart part(ResourceLocation id) {
        return PARTS.get(id);
    }

    /**
     * Adds or replaces the parts of a group.
     */
    public static void add(ResourceLocation group, Use... uses) {
        USES.put(group, List.of(uses));
    }

    public static void remove(ResourceLocation group) {
        USES.remove(group);
    }

    /**
     * Where of() looks next when a group has no parts itself: a sub-race's main race, say. Returns null to stop.
     */
    public static void setParent(Function<ResourceLocation, @Nullable ResourceLocation> parent) {
        BodyParts.parent = parent;
    }

    /**
     * the group's own parts, else its parent's (and so on up)
     */
    public static List<Use> of(ResourceLocation group) {
        for (ResourceLocation id = group; id != null; id = parent.apply(id)) {
            List<Use> own = USES.get(id);
            if (own != null) return own;
        }
        return List.of();
    }

    /**
     * What each player wears. Called every frame for every drawn player, so keep it cheap.
     */
    public static void setSource(Function<Player, List<Worn>> source) {
        BodyParts.source = source;
    }

    public static List<Worn> worn(Player player) {
        return source.apply(player);
    }

    /**
     * every use in one look
     */
    public static List<Worn> wear(List<Use> uses, Look look) {
        return uses.stream().map(use -> new Worn(use, look)).toList();
    }
}
