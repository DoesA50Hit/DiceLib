package org._50dozenhits.dice_library.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * Shoves, pulls and leashes that respect knockback resistance (an entity at 1 doesn't move). A shove along the ground
 * posts vanilla's LivingKnockBackEvent first, so whatever cancels or changes knockback from a sword does the same here.
 * pull and leash, which run every tick, don't. Server side. The push reaches a player's client because hurtMarked is
 * set.
 */
public final class Knockback {
    /**
     * away from {@code from} along the ground by {@code back}, and up by {@code up} (blocks per tick, roughly)
     */
    public static void away(LivingEntity target, Vec3 from, double back, double up) {
        Vec3 away = target.position().subtract(from).multiply(1, 0, 1);
        away = away.lengthSqr() < 1.0E-4 ? Vec3.ZERO : away.normalize();
        push(target, away.scale(back).add(0, up, 0));
    }

    /**
     * toward {@code to} along the ground: a pull
     */
    public static void toward(LivingEntity target, Vec3 to, double strength, double up) {
        Vec3 toward = to.subtract(target.position()).multiply(1, 0, 1);
        toward = toward.lengthSqr() < 1.0E-4 ? Vec3.ZERO : toward.normalize();
        push(target, toward.scale(strength).add(0, up, 0));
    }

    /**
     * along {@code direction} on the ground (its up and down is ignored) by {@code strength}, and up by {@code up}. Use it for wind, or any shove
     * that doesn't come from a point
     */
    public static void along(LivingEntity target, Vec3 direction, double strength, double up) {
        Vec3 flat = new Vec3(direction.x, 0, direction.z);
        flat = flat.lengthSqr() < 1.0E-6 ? Vec3.ZERO : flat.normalize();
        push(target, flat.scale(strength).add(0, up, 0));
    }

    /**
     * Draws any entity, projectiles too, toward a point, faster the further away it is (at most {@code strength} blocks a
     * tick) and slowing what it was doing. Call it every tick for a vortex or a black hole. Nothing within half a
     * block.
     */
    public static void pull(Entity entity, Vec3 toward, double strength) {
        Vec3 offset = toward.subtract(entity.position());
        double distance = offset.length();
        if (distance < 0.5) return;
        double resist = entity instanceof LivingEntity living ? 1 - living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) : 1;
        if (resist <= 0) return;
        Vec3 pull = offset.scale(Math.min(strength, distance * 0.15) * resist / distance);
        entity.setDeltaMovement(entity.getDeltaMovement().scale(0.6).add(pull));
        entity.hurtMarked = true;
    }

    /**
     * Keeps the target within {@code radius} of {@code anchor}, like a chain: past it, the way out is stopped and it is drawn
     * back, harder the further past. Call it every tick while it holds.
     */
    public static void leash(LivingEntity target, Vec3 anchor, double radius) {
        Vec3 offset = anchor.subtract(target.position());
        double distance = offset.length();
        if (distance <= radius || distance < 1.0E-4) return;
        double resist = Math.max(0, 1 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        if (resist <= 0) return;
        Vec3 in = offset.scale(1 / distance);
        Vec3 motion = target.getDeltaMovement();
        double out = -motion.dot(in);
        double back = Math.min(0.6, (distance - radius) * 0.25) * resist;
        target.setDeltaMovement(motion.add(in.scale(Math.max(0, out) * resist + back)));
        target.hurtMarked = true;
    }

    /**
     * any push, scaled down by knockback resistance
     */
    public static void push(LivingEntity target, Vec3 velocity) {
        double resist = Math.max(0, 1 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        if (resist <= 0) return;
        double flat = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (flat > 1.0E-6) {
            // the event's ratio points from the target back at the attacker, the opposite of the push
            LivingKnockBackEvent event = CommonHooks.onLivingKnockBack(target, (float) flat, -velocity.x / flat, -velocity.z / flat);
            if (event.isCanceled()) return;
            double length = Math.sqrt(event.getRatioX() * event.getRatioX() + event.getRatioZ() * event.getRatioZ());
            velocity = length < 1.0E-6 ? new Vec3(0, velocity.y, 0)
                    : new Vec3(-event.getRatioX() / length * event.getStrength(), velocity.y, -event.getRatioZ() / length * event.getStrength());
        }
        target.push(velocity.x * resist, velocity.y * resist, velocity.z * resist);
        target.hurtMarked = true;
    }
}
