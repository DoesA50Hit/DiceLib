package org._50dozenhits.dice_library.examples;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org._50dozenhits.dice_library.dialogue.Dialogues;
import org._50dozenhits.dice_library.dialogue.Dialogues.DialogueEvent;
import org._50dozenhits.dice_library.dialogue.Dialogues.EndReason;
import org._50dozenhits.dice_library.ui.UiActions;

// From the DiceLib Guide, Dialogue. docs/build_guide.py copies this class back into that page.
@EventBusSubscriber(modid = MyMod.MODID)
public final class FerrymanDialogue {
    // the tree in data/mymod/dicelib/dialogues/example/ferryman.json
    public static final ResourceLocation TREE = MyMod.id("example/ferryman");

    // Call once from the mod constructor. It registers an action and a condition that the tree's JSON names by id.
    public static void init() {
        // An action runs on the server when a node is entered or a choice is picked.
        // In the JSON: {"id": "mymod:example_ferry_ticket"}
        UiActions.action(MyMod.id("example_ferry_ticket"), (player, context, args) -> {
            ItemStack ticket = new ItemStack(Items.PAPER);
            ticket.set(DataComponents.CUSTOM_NAME, Component.literal("Ferry Ticket"));
            player.getInventory().add(ticket);
        });
        // A condition decides whether a choice is offered. In the JSON: {"id": "mymod:example_is_night"}
        UiActions.condition(MyMod.id("example_is_night"), (player, context, args) -> player.level().isNight());
    }

    // From now on a right-click on this entity starts the tree. The binding is saved with the entity.
    public static void hire(Entity ferryman) {
        Dialogues.bind(ferryman, TREE);
    }

    // DialogueEvent.End fires however a conversation ends. This one only answers a player who walked out of range.
    @SubscribeEvent
    static void onEnd(DialogueEvent.End event) {
        if (event.getSession().tree().id().equals(TREE) && event.getReason() == EndReason.TOO_FAR) {
            event.getServerPlayer().sendSystemMessage(Component.literal("The ferryman shrugs and goes back to his oars."));
        }
    }
}
