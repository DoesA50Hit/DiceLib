package org._50dozenhits.dice_library.examples;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.IntegerArg;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;

// From the wiki page Command Framework.
@Command("refund")
public class RefundCommand {
    private static final SimpleCommandExceptionType NOTHING_SPENT =
            new SimpleCommandExceptionType(Component.literal("Nothing to refund"));
    private static final DynamicCommandExceptionType TOO_MUCH =
            new DynamicCommandExceptionType(amount -> Component.literal("Can't refund " + amount));

    @Execute
    public boolean refund(@SenderArg ServerPlayer player, @IntegerArg(min = 1) int amount) throws CommandSyntaxException {
        // levels spent at the forge, counted in the player's saved data
        int spent = player.getPersistentData().getInt("mymod_spent");
        if (spent == 0) throw NOTHING_SPENT.create();                 // shows as a red message
        if (amount > spent) throw TOO_MUCH.create(amount);            // shows as a red message
        player.getPersistentData().putInt("mymod_spent", spent - amount);
        player.giveExperienceLevels(amount);
        return true;
    }
}
