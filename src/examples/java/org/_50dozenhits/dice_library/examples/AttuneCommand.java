package org._50dozenhits.dice_library.examples;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;

// From the wiki page Command Framework.
@Command("attune")
public class AttuneCommand {
    // /attune <element>, with suggestions
    @Execute
    public boolean attune(@SenderArg ServerPlayer player, @ElementArg ExampleElement element) {
        player.sendSystemMessage(Component.literal("Attuned to " + element.id()));
        return true;
    }
}
