package org._50dozenhits.dice_library.examples;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.motion.Teleports;
import org._50dozenhits.dice_library.visual.Fx;

// From the wiki page Movement.
// Each method returns false when the caster went nowhere, so the ability that called it can skip its cost.
public final class ExampleShadowStep {
    // Up to 8 blocks ahead, with a trail of smoke behind.
    public static boolean step(LivingEntity caster) {
        return Teleports.blink(caster, 8)
                .flat()               // straight ahead, even when the caster looks up or down
                .throughBlocks()      // a wall doesn't stop it. It lands on the far side, never inside
                .stop()               // no leftover falling or dashing speed after landing
                .trail(MyMod.id("streak"), Fx.Fallback.line(ParticleTypes.SMOKE))   // drawn from start to end
                .sound(SoundEvents.ENDERMAN_TELEPORT, 0.8f, 1.4f)                   // volume 0.8, pitch 1.4
                .go()                 // teleports, and returns where the caster landed
                .isPresent();         // empty means no safe spot was found or another mod cancelled it
    }

    // Behind the enemy the caster looks at, turned to face its back.
    public static boolean ambush(LivingEntity caster) {
        LivingEntity target = Targets.lookedAt(caster, 16, Targets.Relation.NON_ALLIES);
        if (target == null) return false;
        return Teleports.behind(caster, target, 1.5)      // 1.5 blocks behind the target
                // no effect where the caster left (null), and a puff of smoke where it arrives
                .fx(null, MyMod.id("ambush"), Fx.Fallback.burst(ParticleTypes.SMOKE, 0.4f, 12))
                .go().isPresent();
    }

    // Trades places with the enemy the caster looks at. Both keep the way they were facing.
    public static boolean swap(LivingEntity caster) {
        LivingEntity target = Targets.lookedAt(caster, 16, Targets.Relation.NON_ALLIES);
        return target != null && Teleports.swap(caster, target);
    }

    private ExampleShadowStep() {
    }
}
