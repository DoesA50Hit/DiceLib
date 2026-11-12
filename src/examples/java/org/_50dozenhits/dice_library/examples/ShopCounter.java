package org._50dozenhits.dice_library.examples;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.hologram.Hologram;
import org._50dozenhits.dice_library.hologram.Holograms;

// From the wiki page Looks and Custom UIs.
public final class ShopCounter {
    private static final String COINS = "mymod_coins";

    // The server side of ShopScreen. The style id picks the screen on each client, the panel holds the one button
    // the screen presses through the normal checks, and onMessage receives what the screen sends.
    public static Hologram open(ServerLevel level, Vec3 at, ServerPlayer customer) {
        Hologram shop = Holograms.at(level, at).style(MyMod.id("shop"))
                .content(p -> p.button("leave", Component.literal("Leave"), (player, hologram) -> hologram.remove()))
                .onMessage(ShopCounter::message)
                .spawn();
        showCoins(shop, customer);
        return shop;
    }

    // Runs when a client's ShopScreen calls send(action, data). The action and the tag are chosen by the client, so
    // every value is checked here before anything is given.
    private static void message(ServerPlayer player, Hologram shop, String action, CompoundTag data) {
        if (!action.equals("buy")) return;
        int amount;
        try {
            amount = Integer.parseInt(data.getString("amount"));     // the text box can hold anything
        } catch (NumberFormatException e) {
            return;
        }
        int coins = player.getPersistentData().getInt(COINS);
        if (amount < 1 || amount > 64 || amount > coins) return;      // no free or negative orders
        player.getPersistentData().putInt(COINS, coins - amount);
        player.getInventory().add(new ItemStack(Items.PAPER, amount));
        showCoins(shop, player);                                      // the screen redraws with the new total
    }

    // data(player, tag) sends a tag to one viewer only, so each player sees their own coins on the same hologram
    private static void showCoins(Hologram shop, ServerPlayer player) {
        CompoundTag own = new CompoundTag();
        own.putInt("coins", player.getPersistentData().getInt(COINS));
        shop.data(player, own);
    }

    private ShopCounter() {
    }
}
