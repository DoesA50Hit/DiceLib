package org._50dozenhits.dice_library.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredRegister;
import org._50dozenhits.dice_library.DiceLib;

public final class DiceRegistries {
    /**
     * DiceLib's own status effects (Heals.EXTRA_ABSORPTION)
     */
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, DiceLib.MODID);
}
