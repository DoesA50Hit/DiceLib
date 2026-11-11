package org._50dozenhits.dice_library.motion;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.combat.Targets.Relation;
import org._50dozenhits.dice_library.state.CasterState;
import org._50dozenhits.dice_library.state.Scheduler;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The caster carried by the server for a few ticks, for a lunge or a pounce. Each tick
 * it sets the caster's motion (and tells a player's client), keeps its fall from counting, and at the end stops it
 * where it is. A caster has one dash at a time: starting another interrupts the one running. Server side.
 *
 * <pre>{@code
 * Dashes.along(caster, BodyPoints.flatLook(caster).scale(0.9), 8)
 *         .onHit(Relation.NON_ALLIES, hit -> hit.hurt(caster.damageSources().mobAttack(caster), 4)).start();
 * Dashes.to(caster, target, 1.2, 20).onContact(hit -> hit.hurt(caster.damageSources().mobAttack(caster), 8)).start();
 * }</pre>
 */
public final class Dashes {
    /**
     * how a dash stopped
     */
    public enum End {
        /**
         * its ticks ran out
         */
        DONE,
        /**
         * a dash to a target reached it
         */
        CONTACT,
        /**
         * a dash to a target lost it
         */
        TARGET_GONE,
        /**
         * it ran into a wall (stopAtWalls)
         */
        WALL,
        /**
         * another dash started, Dashes.cancel was called, or the caster died or left
         */
        INTERRUPTED
    }

    // the dash each caster is in. Replacing or clearing one interrupts it
    private static final CasterState<Dash> ACTIVE = CasterState.create((id, dash) -> dash.interrupt());

    /**
     * whether the caster is in a dash now
     */
    public static boolean dashing(LivingEntity caster) {
        Dash dash = ACTIVE.get(caster);
        return dash != null && !dash.ended && (dash.task == null || !dash.task.isDone());
    }

    /**
     * Stops the caster's dash where it is, running its onEnd with INTERRUPTED. False when it wasn't dashing.
     */
    public static boolean cancel(LivingEntity caster) {
        return ACTIVE.remove(caster) != null;
    }

    /**
     * Along {@code velocity} (blocks a tick) for {@code ticks}. With no up or down in it, gravity still works on the caster, so it
     * runs along the ground. With some, the caster flies that way.
     */
    public static Dash along(LivingEntity caster, Vec3 velocity, int ticks) {
        return new Dash(caster, velocity, null, 0, ticks);
    }

    /**
     * Straight at {@code target}, {@code speed} blocks a tick, for at most {@code maxTicks}. It stops when it touches the target
     * (onContact) or the target is gone.
     */
    public static Dash to(LivingEntity caster, LivingEntity target, double speed, int maxTicks) {
        return new Dash(caster, Vec3.ZERO, target, speed, maxTicks);
    }

    public static final class Dash {
        private final LivingEntity caster;
        private final Vec3 velocity;
        @Nullable
        private final LivingEntity target;
        private final double speed;
        private final int ticks;
        private Relation relation = Relation.NON_ALLIES;
        @Nullable
        private Consumer<LivingEntity> onHit;
        @Nullable
        private Consumer<LivingEntity> onContact;
        @Nullable
        private Consumer<LivingEntity> onFinish;
        @Nullable
        private BiConsumer<LivingEntity, End> onEnd;
        private boolean stopAtWalls;
        private final Set<UUID> hit = new HashSet<>();
        @Nullable
        private Scheduler.Task task;
        private boolean ended;

        private Dash(LivingEntity caster, Vec3 velocity, @Nullable LivingEntity target, double speed, int ticks) {
            this.caster = caster;
            this.velocity = velocity;
            this.target = target;
            this.speed = speed;
            this.ticks = Math.max(1, ticks);
        }

        /**
         * runs once for each entity of {@code relation} to the caster that it passes through
         */
        public Dash onHit(Relation relation, Consumer<LivingEntity> onHit) {
            this.relation = relation;
            this.onHit = onHit;
            return this;
        }

        /**
         * for to(...): runs with the target when the caster reaches it, and the dash stops there
         */
        public Dash onContact(Consumer<LivingEntity> onContact) {
            this.onContact = onContact;
            return this;
        }

        /**
         * runs with the caster when the dash ends by itself (time up, contact, the target gone, a wall), and not when
         * it is interrupted. onEnd runs either way.
         */
        public Dash onFinish(Consumer<LivingEntity> onFinish) {
            this.onFinish = onFinish;
            return this;
        }

        /**
         * Runs once with the caster and the reason however the dash ends: by itself, or interrupted by another dash,
         * Dashes.cancel, or the caster dying or leaving. Use it to undo whatever the dash granted, such as
         * invulnerability.
         */
        public Dash onEnd(BiConsumer<LivingEntity, End> onEnd) {
            this.onEnd = onEnd;
            return this;
        }

        /**
         * ends the dash (End.WALL) when the caster runs into a block, instead of pushing against it until time is up
         */
        public Dash stopAtWalls() {
            this.stopAtWalls = true;
            return this;
        }

        /**
         * Starts it, interrupting a dash the caster is already in: the first step now, the rest on a Scheduler task
         * bound to the caster. Stop it early with Dashes.cancel(caster), which runs onEnd. Cancelling the task also
         * stops it, without onEnd.
         */
        public Scheduler.Task start() {
            ACTIVE.put(caster, this);
            task = Scheduler.repeat(caster, 1, ticks - 1, (entity, run) -> step(run + 1));
            step(0);
            return task;
        }

        // one tick. True once it ended
        private boolean step(int run) {
            if (ended) return true;
            // the collision is from the caster's last move, so there is none to read on the first step
            if (stopAtWalls && run > 0 && caster.horizontalCollision) return finish(End.WALL);
            if (target != null) {
                if (!target.isAlive() || target.level() != caster.level()) return finish(End.TARGET_GONE);
                if (touching()) {
                    if (onContact != null) onContact.accept(target);
                    return finish(End.CONTACT);
                }
                Vec3 offset = target.position().subtract(caster.position());
                double distance = offset.length();
                caster.setDeltaMovement(distance < 1.0E-4 ? Vec3.ZERO : offset.scale(Math.min(speed, distance) / distance));
            } else if (velocity.y == 0) {
                caster.setDeltaMovement(velocity.x, caster.getDeltaMovement().y, velocity.z);
            } else {
                caster.setDeltaMovement(velocity);
            }
            caster.hurtMarked = true;
            caster.resetFallDistance();
            if (onHit != null) hitAlong();
            if (run >= ticks - 1) return finish(End.DONE);
            return false;
        }

        // what the caster will sweep through this tick
        private void hitAlong() {
            AABB swept = caster.getBoundingBox().expandTowards(caster.getDeltaMovement()).inflate(0.3);
            for (LivingEntity other : caster.level().getEntitiesOfClass(LivingEntity.class, swept,
                    e -> e != caster && Targets.targetable(e) && Targets.matches(caster, e, relation))) {
                if (hit.add(other.getUUID()) && onHit != null) onHit.accept(other);
            }
        }

        private boolean touching() {
            return target != null && caster.getBoundingBox().inflate(0.4).intersects(target.getBoundingBox());
        }

        private boolean finish(End reason) {
            ended = true;
            if (task != null) task.cancel();
            if (ACTIVE.get(caster) == this) ACTIVE.take(caster);
            caster.setDeltaMovement(0, Math.min(0, caster.getDeltaMovement().y), 0);
            caster.hurtMarked = true;
            if (onFinish != null) onFinish.accept(caster);
            if (onEnd != null) onEnd.accept(caster, reason);
            return true;
        }

        // stopped from outside: another dash, Dashes.cancel, or the caster dying or leaving
        private void interrupt() {
            if (ended) return;
            ended = true;
            if (task != null) task.cancel();
            if (caster.isAlive() && !caster.isRemoved()) {
                caster.setDeltaMovement(0, Math.min(0, caster.getDeltaMovement().y), 0);
                caster.hurtMarked = true;
            }
            if (onEnd != null) onEnd.accept(caster, End.INTERRUPTED);
        }
    }
}
