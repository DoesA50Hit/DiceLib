package org._50dozenhits.dice_library;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org._50dozenhits.dice_library.combat.Heals;
import org._50dozenhits.dice_library.command.DiceCommands;
import org._50dozenhits.dice_library.registry.DiceAttachments;
import org._50dozenhits.dice_library.registry.DiceComponents;
import org._50dozenhits.dice_library.registry.DiceEntities;
import org._50dozenhits.dice_library.registry.DiceRegistries;
import org._50dozenhits.dice_library.ui.UiActions;
import org.slf4j.Logger;

/**
 * DiceLib: shared building blocks for mods. Everything registers under the dicelib namespace.
 */
@Mod(DiceLib.MODID)
public class DiceLib {
    public static final String MODID = "dicelib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DiceLib(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, DiceClientConfig.SPEC);

        DiceAttachments.REGISTER.register(modEventBus);
        DiceRegistries.EFFECTS.register(modEventBus);
        Heals.init();
        DiceEntities.REGISTER.register(modEventBus);
        DiceComponents.REGISTER.register(modEventBus);
        UiActions.init();
        DiceCommands.init();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
