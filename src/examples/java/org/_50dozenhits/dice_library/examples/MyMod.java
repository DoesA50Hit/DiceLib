package org._50dozenhits.dice_library.examples;

import net.minecraft.resources.ResourceLocation;

// Stands in for the mod using DiceLib in the guide's examples: its id and its id helper.
public final class MyMod {
    public static final String MODID = "mymod";

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private MyMod() {
    }
}
