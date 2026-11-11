package org._50dozenhits.dice_library.client.ui;

import net.minecraft.resources.ResourceLocation;
import org._50dozenhits.dice_library.client.ui.Look.Background;
import org._50dozenhits.dice_library.client.ui.Look.Box;
import org._50dozenhits.dice_library.client.ui.Look.Buttons;
import org._50dozenhits.dice_library.client.ui.Look.Text;
import org._50dozenhits.dice_library.ui.UiLooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The looks there are, by id (UiLooks). Register your own at client setup. Client only.
 */
public final class Looks {
    public static final Look DEFAULT = new Look(
            new Box(Background.panel(), 6, 4),
            new Box(Background.rect(0xD8C6C6C6), 4, 2),
            new Box(Background.panel(), 6, 6),
            new Box(Background.rect(0xB0000000), 4, 4),
            new Box(Background.panel(), 3, 0),
            new Text(0xFF404040, false),
            new Text(0xFF404040, false),
            new Text(0xFF7A7A7A, false),
            new Text(0xFFFFD36B, true),
            new Text(0xFFFFFFFF, true),
            new Text(0xFF303030, false),
            0x30000000,
            Background.inset(), 7,
            new Buttons(Background.sprite(ResourceLocation.withDefaultNamespace("widget/button")),
                    Background.sprite(ResourceLocation.withDefaultNamespace("widget/button_highlighted")),
                    Background.sprite(ResourceLocation.withDefaultNamespace("widget/button_disabled")),
                    new Text(0xFFFFFFFF, true), new Text(0xFFA0A0A0, true)),
            16, 18);

    public static final Look GLASS = DEFAULT.toBuilder()
            .backgrounds(Background.rounded(0xD0081C22, 3, 1, 0x9946E6FF))
            .title(new Text(0xFF7FF3FF, false))
            .subtitleSpeaker(new Text(0xFF7FF3FF, false))
            .text(new Text(0xFFE6FBFF, false))
            .subtitleText(new Text(0xFFE6FBFF, false))
            .bubbleText(new Text(0xFFE6FBFF, false))
            .dim(new Text(0xFF6FB0BD, false))
            .divider(0x5546E6FF)
            .bar(Background.rounded(0xA0061418, 1, 1, 0x6646E6FF), 7)
            .button(new Buttons(Background.rounded(0xC0103038, 2, 1, 0x8846E6FF),
                    Background.rounded(0xE0185060, 2, 1, 0xFF7FF3FF),
                    Background.rounded(0x80081C22, 2, 1, 0x4446E6FF),
                    new Text(0xFFE6FBFF, false), new Text(0xFF6FB0BD, false)))
            .build();

    private static final Map<ResourceLocation, Look> LOOKS = new ConcurrentHashMap<>();

    static {
        register(UiLooks.DEFAULT, DEFAULT);
        register(UiLooks.GLASS, GLASS);
    }

    public static void register(ResourceLocation id, Look look) {
        LOOKS.put(id, look);
    }

    /**
     * the look by that id, else DEFAULT
     */
    public static Look get(ResourceLocation id) {
        return LOOKS.getOrDefault(id, DEFAULT);
    }

    private Looks() {
    }
}
