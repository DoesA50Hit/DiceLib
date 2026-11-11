package org._50dozenhits.dice_library.client.render.bodypart;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One body part model, such as wings or a tail. Made in Blockbench and exported as GeckoLib files, found by
 * its id ns:name at
 * <pre>{@code
 * assets/ns/geo/bodypart/name.geo.json             the model
 * assets/ns/animations/bodypart/name.animation.json its animations
 * assets/ns/textures/bodypart/name_<color>.png      one per color option; <color>_glow.png for a rare color
 * assets/ns/textures/bodypart/name_mark_<marking>.png one per marking option (drawn over the color)
 * }</pre>
 * and bones named {@code s_<shape>} (or {@code s_<shape>_anything}) for each shape option. The other shapes' bones are hidden.
 * Register it with BodyParts.register and say who wears it with BodyParts.add. Client only.
 */
public final class BodyPart {
    public enum Attach {
        HEAD,
        BODY
    }

    /**
     * Poses bones by hand every frame, after the animations: bone looks a bone up by name, count is the Use's count
     * (how many tails), player the wearer.
     */
    @FunctionalInterface
    public interface BoneHook {
        void pose(Function<String, Optional<GeoBone>> bone, int count, Player player);
    }

    final ResourceLocation id;
    final ResourceLocation geo;
    final ResourceLocation animations;
    final Attach attach;
    final String loop;
    @Nullable
    final Function<Player, String> state;
    final float anchorY;
    final float anchorZ;
    final List<String> fidgets;
    final String hit;
    final Map<String, List<String>> shapeAnimations;
    @Nullable
    final BoneHook bones;

    private BodyPart(Builder b) {
        this.id = b.id;
        this.geo = ResourceLocation.fromNamespaceAndPath(b.id.getNamespace(), "geo/bodypart/" + b.id.getPath() + ".geo.json");
        this.animations = ResourceLocation.fromNamespaceAndPath(b.id.getNamespace(), "animations/bodypart/" + b.id.getPath() + ".animation.json");
        this.attach = b.attach;
        this.loop = b.loop;
        this.state = b.state;
        this.anchorY = b.anchorY;
        this.anchorZ = b.anchorZ;
        this.fidgets = b.fidgets;
        this.hit = b.hit;
        this.shapeAnimations = Map.copyOf(b.shapeAnimations);
        this.bones = b.bones;
    }

    public ResourceLocation id() {
        return id;
    }

    ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/bodypart/" + id.getPath() + "_" + name + ".png");
    }

    // the loop it should play now: what the state function says, else the plain loop
    String loopNow(@Nullable Player player) {
        return state != null && player != null ? state.apply(player) : loop;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /**
     * A loop picked from how the player moves: {@code glide} while gliding or flying, {@code flap} while going up in the air, else
     * {@code idle}. Wings use it. While it picks anything but {@code idle} the part doesn't fidget.
     */
    public static Function<Player, String> flight(String idle, String glide, String flap) {
        return player -> {
            if (player.isFallFlying() || player.getAbilities().flying) return glide;
            if (!player.onGround() && !player.isInWater() && player.getDeltaMovement().y > 0.02) return flap;
            return idle;
        };
    }

    /**
     * Shows bones {@code <prefix>1} .. {@code <prefix>max}, the first {@code count} of them (at least 1), fanned {@code spread} degrees apart
     * around the middle and lifted {@code lift} degrees (every other one 12 more, so a full fan doesn't stack flat). Nine
     * tails sit well with ("tail_", 9, 13, -55).
     */
    public static BoneHook fan(String prefix, int max, float spread, float lift) {
        return (bone, count, player) -> {
            int shown = Math.max(1, count);
            for (int i = 1; i <= max; i++) {
                int index = i - 1;
                boolean visible = i <= shown;
                bone.apply(prefix + i).ifPresent(b -> {
                    b.setHidden(!visible);
                    b.setRotY((index - (shown - 1) / 2f) * spread * Mth.DEG_TO_RAD);
                    b.setRotX((lift - (index % 2) * 12) * Mth.DEG_TO_RAD);
                });
            }
        };
    }

    public static final class Builder {
        private final ResourceLocation id;
        private Attach attach = Attach.HEAD;
        private String loop = "idle";
        @Nullable
        private Function<Player, String> state;
        private float anchorY;
        private float anchorZ;
        private List<String> fidgets = List.of();
        private String hit = "hit";
        private final Map<String, List<String>> shapeAnimations = new HashMap<>();
        @Nullable
        private BoneHook bones;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        /**
         * hangs on the head (the default) or the body
         */
        public Builder on(Attach attach) {
            this.attach = attach;
            return this;
        }

        /**
         * the animation it loops (default "idle")
         */
        public Builder loop(String loop) {
            this.loop = loop;
            return this;
        }

        /**
         * the loop picked each frame from the player, e.g. flight("idle", "glide", "flap"); {@code loop} is its resting one
         */
        public Builder loop(String resting, Function<Player, String> state) {
            this.loop = resting;
            this.state = state;
            return this;
        }

        /**
         * the point (Blockbench pixels, y up from the neck at 24, z) a size option scales around
         */
        public Builder anchor(float y, float z) {
            this.anchorY = y;
            this.anchorZ = z;
            return this;
        }

        /**
         * animations played once every 8 to 20 seconds while resting
         */
        public Builder fidgets(String... fidgets) {
            this.fidgets = List.of(fidgets);
            return this;
        }

        /**
         * the animation played once when the wearer is hurt (default "hit"). Leave it out of the file for none
         */
        public Builder hit(String hit) {
            this.hit = hit;
            return this;
        }

        /**
         * A shape with its own animations: while worn, every animation {@code <name>} plays {@code <name>_<shape>} instead, and it
         * fidgets with these. Sixfold wings used it, each of the six keeping its own time.
         */
        public Builder shapeAnimations(String shape, String... fidgets) {
            shapeAnimations.put(shape, List.of(fidgets));
            return this;
        }

        public Builder bones(BoneHook bones) {
            this.bones = bones;
            return this;
        }

        public BodyPart build() {
            return new BodyPart(this);
        }
    }
}
