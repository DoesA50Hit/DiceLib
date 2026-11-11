package org._50dozenhits.dice_library.examples;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.combat.Knockback;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.motion.Dashes;
import org._50dozenhits.dice_library.visual.BodyPoints;

// From the DiceLib Guide, Movement. docs/build_guide.py copies this class back into that page.
public final class ExampleLunge {
    // A short dash along the ground that cuts each enemy in the way once.
    public static void lunge(LivingEntity caster) {
        // flatLook is the way the caster looks with the up and down taken out, one block long.
        // Scaled by 0.9 it becomes the dash's speed in blocks a tick.
        Vec3 velocity = BodyPoints.flatLook(caster).scale(0.9);
        Dashes.along(caster, velocity, 6)                 // 6 ticks at 0.9 is a bit over 5 blocks
                // runs once for each non-ally the caster passes through
                .onHit(Targets.Relation.NON_ALLIES, hit -> hit.hurt(caster.damageSources().mobAttack(caster), 4))
                .start();                                 // the dash only begins here
    }

    // A leap at the enemy the caster looks at, with a heavy blow on arrival. False when there is no one to leap at,
    // so the ability can skip its cooldown.
    public static boolean pounce(LivingEntity caster) {
        LivingEntity target = Targets.lookedAt(caster, 12, Targets.Relation.NON_ALLIES);   // within 12 blocks
        if (target == null) return false;
        Dashes.to(caster, target, 1.2, 20)                // 1.2 blocks a tick, given up after 20 ticks
                // runs when the caster touches the target, and the dash ends there
                .onContact(hit -> {
                    // hurt returns false when the damage was blocked, and then there is no shove
                    if (hit.hurt(caster.damageSources().mobAttack(caster), 8)) {
                        Knockback.away(hit, caster.position(), 0.8, 0.3);   // 0.8 back from the caster, 0.3 up
                    }
                })
                .start();
        return true;
    }

    private ExampleLunge() {
    }
}
