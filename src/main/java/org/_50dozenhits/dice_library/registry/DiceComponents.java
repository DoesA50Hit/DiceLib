package org._50dozenhits.dice_library.registry;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.state.TempEnchants;

public final class DiceComponents {
    public static final DeferredRegister.DataComponents REGISTER = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, DiceLib.MODID);

    /**
     * enchantments and Unbreakable an item has for a while, and what it had before (state/TempEnchants)
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<TempEnchants.Data>> TEMP_ENCHANTS = REGISTER.registerComponentType(
            "temp_enchants", builder -> builder.persistent(TempEnchants.Data.CODEC).networkSynchronized(TempEnchants.Data.STREAM_CODEC));
}
