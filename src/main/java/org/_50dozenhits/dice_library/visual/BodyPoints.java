package org._50dozenhits.dice_library.visual;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Points on and around an entity, for where an effect, a beam or a thrown thing starts. All in world space.
 */
public final class BodyPoints {
    /**
     * the middle of the chest: 60 % up the bounding box
     */
    public static Vec3 chest(LivingEntity entity) {
        return entity.position().add(0, entity.getBbHeight() * 0.6, 0);
    }

    /**
     * halfway up the bounding box, for any entity (an arrow, a prop)
     */
    public static Vec3 middle(Entity entity) {
        return entity.position().add(0, entity.getBbHeight() / 2, 0);
    }

    /**
     * a point straight ahead of the chest, {@code distance} out the way the entity looks, kept level
     */
    public static Vec3 ahead(LivingEntity entity, double distance) {
        return chest(entity).add(flatLook(entity).scale(distance));
    }

    /**
     * roughly where the hand is when the arm points where the entity looks: the main hand, or the other one
     */
    public static Vec3 hand(LivingEntity entity, boolean mainHand) {
        Vec3 look = entity.getViewVector(1);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
        boolean rightHand = (entity.getMainArm() == HumanoidArm.RIGHT) == mainHand;
        return entity.getEyePosition().add(look.scale(0.6)).add(right.scale(rightHand ? 0.35 : -0.35)).add(0, -0.3, 0);
    }

    /**
     * just above a shoulder (side 1 right, -1 left), where a floating thing waits before it is sent
     */
    public static Vec3 shoulder(LivingEntity entity, int side) {
        return local(entity, 0, 0.6 * side, entity.getBbHeight() * 0.85);
    }

    /**
     * A point in the entity's own frame: {@code forward} along its body's facing (kept level), {@code right} to its right, {@code up}
     * from its feet.
     */
    public static Vec3 local(LivingEntity entity, double forward, double right, double up) {
        float yaw = (float) Math.toRadians(entity.getYRot());
        Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 r = new Vec3(-f.z, 0, f.x);
        return entity.position().add(f.scale(forward)).add(r.scale(right)).add(0, up, 0);
    }

    /**
     * the way it looks with the up and down taken out, as a unit vector (+Z when looking straight up or down)
     */
    public static Vec3 flatLook(LivingEntity entity) {
        Vec3 look = entity.getViewVector(1).multiply(1, 0, 1);
        return look.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : look.normalize();
    }
}
