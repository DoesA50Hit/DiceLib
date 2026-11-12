package org._50dozenhits.dice_library.examples;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org._50dozenhits.dice_library.combat.Heals;

// From the wiki page Healing.
@EventBusSubscriber(modid = MyMod.MODID)
public final class HealerRewards {
    // A player who heals someone else earns a point of experience per health healed. An arrow or a spring of theirs
    // counts too, because getHealer is the player behind whatever the heal came from.
    @SubscribeEvent
    static void onHealed(Heals.HealEvent.Healed event) {
        // getEntity is who was healed. Healing yourself earns nothing.
        if (event.getHealer() instanceof ServerPlayer healer && event.getEntity() != healer) {
            healer.giveExperiencePoints(Math.round(event.getAmount()));
        }
    }

    private HealerRewards() {
    }
}
