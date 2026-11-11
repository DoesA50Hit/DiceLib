package org._50dozenhits.dice_library.examples;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org._50dozenhits.dice_library.client.render.bodypart.BodyPart;
import org._50dozenhits.dice_library.client.render.bodypart.BodyParts;

import java.util.List;

// From the DiceLib Guide, Body Parts. docs/build_guide.py copies this class back into that page.
@EventBusSubscriber(modid = MyMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ExampleParts {
    public static final ResourceLocation HORNS = MyMod.id("horns");
    public static final ResourceLocation WINGS = MyMod.id("wings");
    public static final ResourceLocation TAILS = MyMod.id("tails");

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        // The parts. Each id names a model made in Blockbench.

        // Horns sit on the head, which is the default. anchor is the point a size option scales around, here 28
        // pixels up in the model. The two fidgets are animations played now and then while the player rests.
        BodyParts.register(BodyPart.builder(HORNS).anchor(28, 0).fidgets("fidget_tilt", "fidget_nod").build());
        // Wings sit on the body. flight picks the loop from how the player moves: "glide" while gliding, "flap"
        // while rising, "idle" otherwise. The sixfold shape has its own fidget animations.
        BodyParts.register(BodyPart.builder(WINGS).on(BodyPart.Attach.BODY).anchor(20, 2.5f)
                .loop("idle", BodyPart.flight("idle", "glide", "flap"))
                .fidgets("fidget_stretch", "fidget_ruffle")
                .shapeAnimations("sixfold", "fidget_sixfold_left", "fidget_sixfold_right")
                .build());
        // Tails: the model has bones tail_1 to tail_9. fan shows as many as the wearer's count says and spreads
        // them 13 degrees apart, lifted by -55 degrees.
        BodyParts.register(BodyPart.builder(TAILS).on(BodyPart.Attach.BODY).loop("sway").anchor(12, 2)
                .bones(BodyPart.fan("tail_", 9, 13, -55)).build());

        // Which group wears which parts. A group is any id you choose.
        BodyParts.add(MyMod.id("demon"), BodyParts.use(HORNS));                    // a whole race
        BodyParts.add(MyMod.id("wyrmkin/elder"), BodyParts.use(TAILS, 9));         // one lineage, nine tails
        BodyParts.add(MyMod.id("wyrmkin"), BodyParts.use(TAILS, 3), BodyParts.use(WINGS));

        // A group with no parts uses its parent's. Here the parent is the id with its last "/part" cut off, so
        // mymod:wyrmkin/young falls back to mymod:wyrmkin. Returning null means there is no parent.
        BodyParts.setParent(id -> {
            String path = id.getPath();
            return path.contains("/") ? id.withPath(path.substring(0, path.lastIndexOf('/'))) : null;
        });

        // What each player wears right now. This runs on the client for every player it draws. Here a
        // player's group is their scoreboard team's name. A real mod reads its own synced data instead.
        BodyParts.setSource(player -> {
            if (player.getTeam() == null) return List.of();
            ResourceLocation group = MyMod.id(player.getTeam().getName());
            // the look: colour, whether the colour is rare, markings, whether those are rare, shape, scale
            BodyParts.Look look = new BodyParts.Look("crimson", false, "none", false, "ram", 1);
            return BodyParts.wear(BodyParts.of(group), look);
        });
    }

    private ExampleParts() {
    }
}
