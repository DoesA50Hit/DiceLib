package org._50dozenhits.dice_library.examples;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.prop.Formation;
import org._50dozenhits.dice_library.state.Scheduler;

// From the wiki page Props.
@EventBusSubscriber(modid = MyMod.MODID)
public final class ExampleSwords {
    // The fan. Each new object in it is an iron sword, and one that vanishes plays this effect.
    private static final Formation FAN = Formation.of(caster -> new ItemStack(Items.IRON_SWORD)).vanishFx(MyMod.id("sword_fade"));
    // How many swords are ready. The count lives in the player's saved data, so the fan is rebuilt from it after a
    // restart. The Formation only draws the swords and is never saved.
    private static final String READY = "mymod_swords_ready";
    private static final int MAX = 5;

    // Every tick: a player with a nether star in the off hand has their ready swords fanned out behind them.
    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag data = player.getPersistentData();
        if (!data.contains(READY)) data.putInt(READY, MAX);          // a new player starts with all five
        boolean shown = player.getOffhandItem().is(Items.NETHER_STAR) && !player.isInvisible();
        int count = shown ? data.getInt(READY) : 0;                   // keeping 0 makes the swords vanish
        // keep adds or removes swords until there are `count`, then places each one with the lambda.
        // Here each sword takes its slot in a fan 0.9 blocks long that starts a little above the middle of the back.
        FAN.keep(player, count, (sword, slot, slots) -> sword.fan(player, slot, slots, 0.9f, player.getBbHeight() * 0.62f));
    }

    // Throws one sword where the player looks. It is ready again 4 seconds later. False when none is ready.
    public static boolean send(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.getInt(READY) <= 0) return false;
        data.putInt(READY, data.getInt(READY) - 1);                   // one fewer in the fan from the next tick
        // 80 ticks later the count goes back up. The task belongs to the player and is dropped if they leave.
        Scheduler.after(player, 80, owner -> {
            CompoundTag saved = owner.getPersistentData();
            saved.putInt(READY, Math.min(MAX, saved.getInt(READY) + 1));
        });
        // take pulls the last sword out of the fan, so keep stops moving it and it can fly off
        FAN.take(player).fly(player,
                player.getLookAngle().scale(1.6),                     // 1.6 blocks a tick
                24,                                                   // for up to 24 blocks
                true, Targets.Relation.NON_ALLIES,                    // it stops at the first non-ally
                (hit, at) -> {
                    // hit is null when a block stopped it or it ran out of range
                    if (hit != null) hit.hurt(player.damageSources().playerAttack(player), 5);
                },
                12);                                                  // then it stays stuck there for 12 ticks
        return true;
    }

    private ExampleSwords() {
    }
}
