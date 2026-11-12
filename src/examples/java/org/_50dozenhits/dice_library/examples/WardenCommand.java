package org._50dozenhits.dice_library.examples;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.warden.Warden;
import org._50dozenhits.dice_library.command.CommandRegistry.BlockPosArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.LiteralArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Permission;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;

// From the wiki page Command Framework.
@Command("warden")
@Permission(value = "mymod.warden", permissionLevel = Permission.PermissionLevel.PLAYER)
public class WardenCommand {
    // everyone: /warden
    @Execute
    public boolean info(@SenderArg CommandSourceStack source) {
        return true;
    }

    // ops only: /warden summon <pos>, hidden from everyone else
    @Execute
    @Permission(value = "mymod.warden.summon", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
    public boolean summon(@SenderArg CommandSourceStack source, @LiteralArg("summon") String l, @BlockPosArg BlockPos pos) {
        Warden warden = EntityType.WARDEN.create(source.getLevel());
        if (warden == null) return false;
        warden.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        return source.getLevel().addFreshEntity(warden);
    }
}
