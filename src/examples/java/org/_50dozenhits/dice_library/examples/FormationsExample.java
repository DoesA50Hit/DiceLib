package org._50dozenhits.dice_library.examples;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org._50dozenhits.dice_library.prop.Formation;

// From the wiki page Props.
public final class FormationsExample {
    // One Formation per kind of set, kept in a static field. The lambda says what each new object shows for a caster.
    public static final Formation SWORDS = Formation.of(caster -> new ItemStack(Items.IRON_SWORD));
}
