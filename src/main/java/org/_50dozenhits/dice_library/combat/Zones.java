package org._50dozenhits.dice_library.combat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.state.Scheduler;
import org._50dozenhits.dice_library.visual.Fx;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An area that lasts and pulses, such as a healing spring or a burning circle. Each pulse
 * plays its effect at the area's size and runs its rules on whoever stands in it. The first pulse is now. onEnter and
 * onExit say who came and went between pulses. Server side.
 *
 * <pre>{@code
 * Zones.at(level, centre, 4).every(20).times(5)
 *         .fx(fx("smog"), Fx.Fallback.ring(ParticleTypes.SMOKE, 4))
 *         .onEnemies(caster, (target, pulse) -> target.hurt(caster.damageSources().mobAttack(caster), 2))
 *         .onAllies(caster, (ally, pulse) -> Heals.of(caster, ally, 1).heal())
 *         .start();
 * }</pre>
 *
 * <p>A zone is in memory only, like Scheduler's tasks: a server stop ends it.
 */
public final class Zones {
    /**
     * A zone at a fixed point (the middle of its floor).
     */
    public static Zone at(ServerLevel level, Vec3 center, double radius) {
        return new Zone(level, center, radius);
    }

    /**
     * A zone that goes where {@code entity} goes, around its feet, and ends when the entity dies or is removed.
     */
    public static Zone on(LivingEntity entity, double radius) {
        return new Zone((ServerLevel) entity.level(), entity.position(), radius).follow(entity);
    }

    /**
     * what a rule does to one entity, on pulse {@code pulse} (from 0)
     */
    @FunctionalInterface
    public interface Pulse {
        void apply(LivingEntity entity, int pulse);
    }

    private record Rule(Predicate<LivingEntity> which, Pulse pulse) {
    }

    public static final class Zone {
        private final ServerLevel level;
        private final Vec3 center;
        private final double radius;
        @Nullable
        private Entity follow;
        private double height = -1;
        private int every = 20;
        private int times = 1;
        // ticks it pulses for when lasting(...) was called, else -1
        private int lasting = -1;
        @Nullable
        private ResourceLocation fx;
        @Nullable
        private Fx.Fallback fallback;
        @Nullable
        private SoundEvent sound;
        private float volume = 1;
        private float pitch = 1;
        private final List<Rule> rules = new ArrayList<>();
        @Nullable
        private BiConsumer<Vec3, Integer> onPulse;
        private boolean hitboxes;
        @Nullable
        private Consumer<LivingEntity> onEnter;
        @Nullable
        private Consumer<LivingEntity> onExit;
        // who was in it at the last pulse, for onEnter and onExit
        private final Map<UUID, LivingEntity> within = new HashMap<>();
        private int pulses = 1;

        private Zone(ServerLevel level, Vec3 center, double radius) {
            this.level = level;
            this.center = center;
            this.radius = radius;
        }

        /**
         * ticks between pulses (default 20)
         */
        public Zone every(int ticks) {
            this.every = Math.max(1, ticks);
            return this;
        }

        /**
         * how many pulses in all, the first one now (default 1)
         */
        public Zone times(int pulses) {
            this.times = Math.max(1, pulses);
            this.lasting = -1;
            return this;
        }

        /**
         * Pulses for {@code ticks}: one now and one every {@code every} ticks until then, whichever of the two is set first.
         */
        public Zone lasting(int ticks) {
            this.lasting = Math.max(0, ticks);
            return this;
        }

        /**
         * moves with {@code entity}, at its feet, and ends when it dies or is removed
         */
        public Zone follow(Entity entity) {
            this.follow = entity;
            return this;
        }

        /**
         * An upright cylinder from the floor up {@code blocks}, instead of a ball, so things above and below are out.
         */
        public Zone height(double blocks) {
            this.height = blocks;
            return this;
        }

        /**
         * played at the middle of the floor each pulse, stretched to the radius
         */
        public Zone fx(ResourceLocation id, Fx.Fallback fallback) {
            this.fx = id;
            this.fallback = fallback;
            return this;
        }

        public Zone sound(SoundEvent sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
            return this;
        }

        /**
         * each pulse, on everyone in it not allied with {@code caster} (never the caster)
         */
        public Zone onEnemies(LivingEntity caster, Pulse pulse) {
            rules.add(new Rule(e -> e != caster && !Targets.isAlly(caster, e), pulse));
            return this;
        }

        /**
         * each pulse, on {@code caster} and its allies standing in it
         */
        public Zone onAllies(LivingEntity caster, Pulse pulse) {
            rules.add(new Rule(e -> Targets.isAlly(caster, e), pulse));
            return this;
        }

        /**
         * each pulse, on everyone in it that {@code which} lets through
         */
        public Zone on(Predicate<LivingEntity> which, Pulse pulse) {
            rules.add(new Rule(which, pulse));
            return this;
        }

        /**
         * Counts anyone whose hitbox reaches into the ball, not only those whose feet are in it (Targets.touching), so a
         * big mob at the edge is caught. A zone with a height still goes by the feet.
         */
        public Zone hitboxes() {
            this.hitboxes = true;
            return this;
        }

        /**
         * Runs for each entity found in the zone at a pulse that wasn't in it at the pulse before, the first pulse
         * included: for a slow or a mark that lasts while they stay. Seen once a pulse, so as fine as every(...).
         */
        public Zone onEnter(Consumer<LivingEntity> onEnter) {
            this.onEnter = onEnter;
            return this;
        }

        /**
         * Runs for each entity that was in the zone at the pulse before and is gone at this one, and for everyone
         * still in it at the last pulse. Not when the zone's task is cancelled or its followed entity is removed.
         */
        public Zone onExit(Consumer<LivingEntity> onExit) {
            this.onExit = onExit;
            return this;
        }

        /**
         * each pulse, with where the zone is now and the pulse number: for blocks, items or anything else
         */
        public Zone onPulse(BiConsumer<Vec3, Integer> onPulse) {
            this.onPulse = onPulse;
            return this;
        }

        /**
         * Starts it: the first pulse now, the rest on a Scheduler task. Cancel the task to end it early.
         */
        public Scheduler.Task start() {
            pulses = lasting >= 0 ? Math.max(1, lasting / every) : times;
            within.clear();
            pulse(0);
            if (follow != null) return Scheduler.repeat(follow, every, pulses - 1, (entity, run) -> pulse(run + 1));
            return Scheduler.repeat(every, pulses - 1, run -> pulse(run + 1));
        }

        /**
         * where it is now: on the followed entity's feet, or where it was put
         */
        public Vec3 center() {
            return follow != null ? follow.position() : center;
        }

        /**
         * who is in it now, whatever the rules
         */
        public List<LivingEntity> inside() {
            Vec3 at = center();
            if (height < 0) return hitboxes ? Targets.touching(level, at, radius, e -> true) : Targets.around(level, at, radius, e -> true);
            double radiusSqr = radius * radius;
            return Targets.around(level, at.add(0, height / 2, 0), Math.sqrt(radiusSqr + height * height / 4), e -> {
                double dx = e.getX() - at.x, dz = e.getZ() - at.z;
                return dx * dx + dz * dz <= radiusSqr && e.getY() >= at.y && e.getY() <= at.y + height;
            });
        }

        private void pulse(int run) {
            if (follow != null && (follow.isRemoved() || follow.level() != level)) return;
            Vec3 at = center();
            if (fx != null && fallback != null) Fx.at(level, fx, at, (float) radius, fallback);
            if (sound != null) Fx.sound(level, at, sound, volume, pitch);
            boolean tracks = onEnter != null || onExit != null;
            if (!rules.isEmpty() || tracks) {
                List<LivingEntity> inside = inside();
                if (tracks) track(inside);
                for (LivingEntity entity : inside) {
                    for (Rule rule : rules) {
                        if (entity.isAlive() && rule.which().test(entity)) rule.pulse().apply(entity, run);
                    }
                }
            }
            if (onPulse != null) onPulse.accept(at, run);
            if (run >= pulses - 1 && !within.isEmpty()) {
                List<LivingEntity> left = List.copyOf(within.values());
                within.clear();
                if (onExit != null) left.forEach(onExit);
            }
        }

        // who came in and who left since the last pulse
        private void track(List<LivingEntity> inside) {
            Map<UUID, LivingEntity> now = new HashMap<>();
            for (LivingEntity entity : inside) now.put(entity.getUUID(), entity);
            List<LivingEntity> entered = new ArrayList<>(), left = new ArrayList<>();
            now.forEach((id, entity) -> {
                if (!within.containsKey(id)) entered.add(entity);
            });
            within.forEach((id, entity) -> {
                if (!now.containsKey(id)) left.add(entity);
            });
            within.clear();
            within.putAll(now);
            if (onExit != null) left.forEach(onExit);
            if (onEnter != null) entered.forEach(onEnter);
        }
    }
}
