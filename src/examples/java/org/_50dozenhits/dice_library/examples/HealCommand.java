package org._50dozenhits.dice_library.examples;

import net.minecraft.server.level.ServerPlayer;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.EntityArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.FloatArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Permission;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;

// From the DiceLib Guide, Command Framework. docs/build_guide.py copies this class back into that page.
@Command("heal")
@Permission(value = "mymod.heal", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
public class HealCommand {
    @Execute                                                          // /heal
    public boolean self(@SenderArg ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        return true;
    }

    @Execute                                                          // /heal <target> <amount>
    public boolean other(@EntityArg(name = "target", value = EntityArg.Type.PLAYER) ServerPlayer target,
                         @FloatArg(min = 0) float amount) {
        target.heal(amount);
        return true;
    }
}
