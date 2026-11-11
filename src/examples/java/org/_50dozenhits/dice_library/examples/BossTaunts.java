package org._50dozenhits.dice_library.examples;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org._50dozenhits.dice_library.dialogue.Speech;
import org._50dozenhits.dice_library.hologram.MobPanels;
import org._50dozenhits.dice_library.hologram.MobPanels.MobPanel;

// From the DiceLib Guide, Dialogue. docs/build_guide.py copies this class back into that page.
public final class BossTaunts {
    // A panel for the fight, shown to everyone within 48 blocks. Keep what show() returns to change it later.
    public static MobPanel showPanel(LivingEntity boss) {
        return MobPanels.on(boss)
                .range(48)
                .pinned(MobPanels.Corner.TOP_LEFT)        // fixed in a screen corner, with a line down to the boss
                .title(Component.literal("The Hollow King"))
                .icon(new ItemStack(Items.WITHER_SKELETON_SKULL))
                .health()                                 // a health bar that each client fills in from the boss itself
                .line("phase", Component.literal("Phase 1"))                  // "phase" is the key used to change it
                .bar("rage", Component.literal("Rage"), 0, 100, 0xFFC03030)   // starts at 0 of 100, in red
                .show();
    }

    // The fight opens with a line in the subtitle box and a bubble over the boss, heard within 32 blocks.
    public static void opening(LivingEntity boss) {
        Speech.of(boss, Component.literal("Another crown-seeker. Kneel, or be knelt."))
                .bubble()
                .voice(SoundEvents.WITHER_AMBIENT, 1.4f)  // a soft sound as the text types, at pitch 1.4
                .send();
    }

    // At half health. interrupt() cuts off a line that is still typing, where a normal line would wait its turn.
    public static void phaseTwo(LivingEntity boss, MobPanel panel) {
        Speech.of(boss, Component.literal("Enough. No more holding back."))
                .range(48).bubble().interrupt().voice(SoundEvents.WITHER_AMBIENT, 1.1f).send();
        // the same keys as above. Both changes go out to viewers together at the end of the tick
        panel.set("phase", Component.literal("Phase 2")).bar("rage", 100);
    }
}
