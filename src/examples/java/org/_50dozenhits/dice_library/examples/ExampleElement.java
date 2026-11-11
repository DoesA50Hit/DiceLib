package org._50dozenhits.dice_library.examples;

import java.util.Locale;

// From the DiceLib Guide, Command Framework. docs/build_guide.py copies this class back into that page.
public enum ExampleElement {
    METAL, STEAM, INK, MUSIC;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
