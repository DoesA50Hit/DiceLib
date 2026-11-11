package org._50dozenhits.dice_library.examples;

import org._50dozenhits.dice_library.prop.TetherLook;

// From the DiceLib Guide, Props. docs/build_guide.py copies this class back into that page.
public final class ExampleTethers {
    // A chain of thorn links. Each link is 0.26 blocks across and 0.4 long, with one every 0.28 blocks, so they
    // overlap a little. minLight(6) keeps it readable in the dark, and breakFx plays where a breakable tether snaps.
    public static final TetherLook THORNS = TetherLook.register(TetherLook.links(MyMod.id("thorns"),
            MyMod.id("textures/tether/thorns.png"), 0.26f, 0.4f, 0.28f).minLight(6).breakFx(MyMod.id("example/thorns_break")));
    // A string of signs. The texture holds four of them side by side (the last argument). Each is drawn 0.34 across
    // and 0.34 long, with one every 0.32 blocks.
    public static final TetherLook SCRIPT = TetherLook.register(TetherLook.glyphs(MyMod.id("script"),
            MyMod.id("textures/tether/script.png"), 0.34f, 0.34f, 0.32f, 4));
    // One smooth band of light, always drawn at full brightness.
    public static final TetherLook LIGHT = TetherLook.register(TetherLook.ribbon(MyMod.id("light"), MyMod.id("textures/tether/light.png")).minLight(15));
}
