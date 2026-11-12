package org._50dozenhits.dice_library.examples;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.EntityArg;
import org._50dozenhits.dice_library.command.CommandRegistry.EnumArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.IntegerArg;
import org._50dozenhits.dice_library.command.CommandRegistry.LiteralArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Permission;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;
import org._50dozenhits.dice_library.command.CommandRegistry.TextArg;

// From the wiki page Command Framework.
@Command(value = {"soulforge", "sf"}, subCommands = {SoulforgeCommand.Status.class, SoulforgeCommand.Tune.class})
@Permission(value = "mymod.soulforge", permissionLevel = Permission.PermissionLevel.PLAYER)
public class SoulforgeCommand {
    public enum Element { METAL, STEAM, INK }

    // /soulforge  (and /sf)
    @Execute
    public boolean help(@SenderArg CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Use /soulforge status or /soulforge tune"), false);
        return true;
    }

    @Command("status")
    public static class Status {
        // /soulforge status
        @Execute
        public boolean self(@SenderArg ServerPlayer player) {
            player.sendSystemMessage(Component.literal("Level " + player.experienceLevel));
            return true;
        }

        // /soulforge status <player>
        @Execute
        @Permission(value = "mymod.soulforge.status.others", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean other(@SenderArg CommandSourceStack source,
                             @EntityArg(name = "player", value = EntityArg.Type.PLAYER) ServerPlayer player) {
            source.sendSuccess(() -> Component.literal(player.getScoreboardName() + ": level " + player.experienceLevel), false);
            return true;
        }
    }

    @Command("tune")
    public static class Tune {
        // /soulforge tune up <amount>   and   /soulforge tune down <amount>
        @Execute
        public int shift(@SenderArg ServerPlayer player, @LiteralArg({"up", "down"}) String direction,
                         @IntegerArg(min = 1, max = 10) int amount) {
            int delta = direction.equals("up") ? amount : -amount;
            player.giveExperienceLevels(delta);
            return player.experienceLevel;             // the int becomes the command result
        }

        // /soulforge tune element <metal|steam|ink>
        @Execute
        public boolean element(@SenderArg ServerPlayer player, @LiteralArg("element") String literal, @EnumArg Element element) {
            player.getPersistentData().putString("mymod_tuned", element.name());
            return true;
        }

        // /soulforge tune name <word>   and   /soulforge tune name <word> <times>
        @Execute
        public boolean name(@SenderArg ServerPlayer player, @LiteralArg("name") String literal, @TextArg(name = "word") String word) {
            return name(player, literal, word, 1);
        }

        @Execute
        public boolean name(@SenderArg ServerPlayer player, @LiteralArg("name") String literal, @TextArg(name = "word") String word,
                            @IntegerArg(value = "times", min = 1, max = 5) int times) {
            player.sendSystemMessage(Component.literal(word.repeat(times)));
            return true;
        }
    }
}
