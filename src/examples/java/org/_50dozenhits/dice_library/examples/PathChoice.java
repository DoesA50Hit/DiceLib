package org._50dozenhits.dice_library.examples;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.hologram.Hologram;
import org._50dozenhits.dice_library.hologram.Holograms;
import org._50dozenhits.dice_library.ui.Audience;
import org._50dozenhits.dice_library.ui.UiActions;

// From the wiki page Holograms.
public final class PathChoice {
    // A menu in front of one player that only they see. It belongs to a quest step: it closes when the player's
    // "on_crossroads_quest" flag is cleared, and by itself after 30 seconds untouched.
    public static Hologram open(ServerPlayer player) {
        Vec3 eyes = player.getEyePosition();
        // two blocks ahead at eye height: the look direction flattened, made one block long, then doubled
        Vec3 spot = eyes.add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(2));
        // the arguments of the built-in dicelib:flag condition, written the way a JSON file would give them
        JsonObject quest = new JsonObject();
        quest.addProperty("flag", "on_crossroads_quest");
        return Holograms.at(player.serverLevel(), spot)
                .facingToward(eyes)                       // turned to the player once. It doesn't follow them
                .viewers(Audience.players(player))        // nobody else sees it
                .idleTimeout(600)                         // removed after 30 seconds with no button pressed
                // removed as soon as the condition fails for the player, which is when the flag is cleared
                .tiedTo(player, UiActions.ref("dicelib:flag", quest))
                // a button has an id, a label, and the code a press runs on the server
                .content(p -> p.title(Component.literal("Choose a path"))
                        .button("left", Component.literal("Left"), (chooser, menu) -> choose(chooser, menu, "left"))
                        .button("right", Component.literal("Right"), (chooser, menu) -> choose(chooser, menu, "right")))
                .onRemove((menu, reason) -> {
                    // only the timeout gets a message. Picking a path closes the menu without one
                    if (reason == Hologram.RemoveReason.IDLE) player.sendSystemMessage(Component.literal("The choice fades."));
                })
                .spawn();
    }

    private static void choose(ServerPlayer player, Hologram menu, String path) {
        player.sendSystemMessage(Component.literal("You take the " + path + " path."));
        UiActions.setFlag(player, "on_crossroads_quest", false);   // the quest step is over
        menu.remove();
    }

    private PathChoice() {
    }
}
