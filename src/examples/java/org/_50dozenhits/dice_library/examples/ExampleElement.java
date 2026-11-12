package org._50dozenhits.dice_library.examples;

import java.util.Locale;

// From the wiki page Command Framework.
public enum ExampleElement {
    METAL, STEAM, INK, MUSIC;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
