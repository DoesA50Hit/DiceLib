package org._50dozenhits.dice_library.examples;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org._50dozenhits.dice_library.combat.Heals;
import org._50dozenhits.dice_library.combat.Zones;
import org._50dozenhits.dice_library.visual.Fx;

// From the wiki page Zones.
public final class MendingSpring {
    // A healing spring at the caster's feet for 5 seconds. Call it from an item's use or a boss's attack.
    public static void place(LivingEntity caster) {
        if (!(caster.level() instanceof ServerLevel level)) return;   // zones run on the server
        Zones.at(level, caster.position(), 4)             // a circle 4 blocks in radius
                .every(20)                                // one pulse a second
                .lasting(100)                             // for 5 seconds. The first pulse is immediate
                .fx(MyMod.id("spring"), Fx.Fallback.ring(ParticleTypes.SPLASH, 4))   // played at each pulse
                // Each pulse heals the caster and every ally in the circle by 1. What doesn't fit under their max
                // health stays as absorption for 100 ticks.
                .onAllies(caster, (ally, pulse) -> Heals.of(caster, ally, 1).overheal(100).heal())
                // Everyone else in it is slowed. 30 ticks is longer than the gap between pulses, so the slow holds for
                // as long as they stand there.
                .onEnemies(caster, (enemy, pulse) -> enemy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1), caster))
                .start();
    }

    private MendingSpring() {
    }
}
