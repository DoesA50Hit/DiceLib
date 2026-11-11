package org._50dozenhits.dice_library.combat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.registry.DiceEntities;
import org.jetbrains.annotations.Nullable;

/**
 * Projectiles for abilities, flying straight, in an arc or after a target. A Shot has no model. Give it a trail and that is
 * what players see. It hits by its owner's relations, hurts through Hits so the owner is the attacker, and can pierce.
 * Server side.
 *
 * <pre>{@code
 * // a bolt from the caster's hand that pierces two enemies and bursts on whatever stops it
 * Shots.from(caster, BodyPoints.hand(caster, true), caster.getLookAngle().scale(1.5))
 *         .range(24).pierce(2).damage(5)
 *         .trail(MyMod.id("bolt"), Fx.Fallback.line(ParticleTypes.ELECTRIC_SPARK))
 *         .onEnd((shot, end) -> Fx.at(level, MyMod.id("burst"), shot.position(), 1, Fx.Fallback.burst(ParticleTypes.FLASH, 0, 1)))
 *         .fire();
 * }</pre>
 */
public final class Shots {
    /**
     * How a shot ended
     */
    public enum End {
        /**
         * it flew its range
         */
        RANGE,
        /**
         * its life ran out
         */
        EXPIRED,
        /**
         * a block stopped it
         */
        BLOCK,
        /**
         * an entity stopped it: it had no pierce left
         */
        HIT,
        /**
         * stop() was called
         */
        STOPPED
    }

    /**
     * A shot from {@code from} flying along {@code velocity} (blocks per tick), owned by {@code owner}. Set it up, then
     * fire(). Server side only.
     */
    public static Shot from(Entity owner, Vec3 from, Vec3 velocity) {
        return from((ServerLevel) owner.level(), owner, from, velocity);
    }

    /**
     * the same with no owner (a trap): it hits by relation ANY unless told otherwise, and its hits
     * have no attacker
     */
    public static Shot from(ServerLevel level, @Nullable Entity owner, Vec3 from, Vec3 velocity) {
        Shot shot = new Shot(DiceEntities.SHOT.get(), level);
        shot.aim(owner, from, velocity);
        if (owner == null) shot.relation(Targets.Relation.ANY);
        return shot;
    }

    private Shots() {
    }
}
