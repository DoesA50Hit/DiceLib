package org._50dozenhits.dice_library;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Each player's own settings (config/dicelib-client.toml). Only the client reads these.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class DiceClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.EnumValue<BodyParts> BODY_PARTS = BUILDER
            .comment("Whose body parts (wings, horns, tails) are drawn: ALL, OTHERS (everyone but you) or NONE")
            .defineEnum("bodyParts.show", BodyParts.ALL);

    private static final ModConfigSpec.BooleanValue SPEECH_BUBBLES = BUILDER
            .comment("Whether spoken lines show a speech bubble over the speaker's head")
            .define("speech.bubbles", true);

    private static final ModConfigSpec.IntValue TYPE_SPEED = BUILDER
            .comment("Characters per second that spoken lines and dialogue type out at; 0 shows each line whole")
            .defineInRange("speech.typeSpeed", 45, 0, 1000);

    static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * the default until the config has loaded
     */
    public static BodyParts bodyParts = BodyParts.ALL;
    public static boolean speechBubbles = true;
    public static int typeSpeed = 45;

    public enum BodyParts {
        ALL,
        OTHERS,
        NONE
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading || event.getConfig().getSpec() != SPEC) return;
        bodyParts = BODY_PARTS.get();
        speechBubbles = SPEECH_BUBBLES.get();
        typeSpeed = TYPE_SPEED.get();
    }
}
