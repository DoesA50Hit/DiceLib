package org._50dozenhits.dice_library.examples;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.hologram.Hologram;
import org._50dozenhits.dice_library.hologram.Holograms;
import org._50dozenhits.dice_library.ui.Audience;
import org._50dozenhits.dice_library.ui.UiActions;

import java.util.List;

// From the wiki page Holograms.
public final class PartyBoard {
    // A board on a wall. Players tagged "party" see it and can press its buttons. Operators see it too but can't
    // press anything. Anyone in the party can leave, and only a player flagged as leader can open the way down.
    public static Hologram open(ServerLevel level, Vec3 wall) {
        // arguments for two built-in UiActions, written the way a JSON file would give them
        JsonObject leave = new JsonObject();
        leave.addProperty("command", "tag @s remove party");          // dicelib:command runs this as the presser
        JsonObject leader = new JsonObject();
        leader.addProperty("flag", "party_leader");                   // dicelib:flag passes for players with the flag
        return Holograms.at(level, wall)
                .facing(Direction.NORTH)                  // fixed, like a sign
                .width(150)                               // GUI pixels across
                .range(16)                                // seen from up to 16 blocks away
                .viewers(Audience.anyOf(Audience.tagged("party"), Audience.permission(2)))   // the party, and operators
                .interactors(Audience.tagged("party"))                                       // only the party presses
                .content(p -> p.title(Component.literal("Drowned Halls"))
                        .text("party", Component.literal("Party: 1/4"))    // "party" is the key joined() changes
                        .divider()
                        // greyed out for a viewer unless every condition in the list passes for them
                        .button("descend", Component.literal("Descend"), PartyBoard::descend, List.of(UiActions.ref("dicelib:flag", leader)))
                        // this button runs a UiActions call where the others run Java code
                        .button("leave", Component.literal("Leave the party"), UiActions.ref("dicelib:command", leave)))
                .spawn();
    }

    // Changes one line by its key. Everyone looking at the board sees the new text.
    public static void joined(Hologram board, int members) {
        board.text("party", Component.literal("Party: " + members + "/4"));
    }

    // update replaces the whole panel, so the buttons are gone afterwards
    private static void descend(ServerPlayer leader, Hologram board) {
        board.update(p -> p.title(Component.literal("Drowned Halls")).text(Component.literal("The way down is open.")));
    }
}
