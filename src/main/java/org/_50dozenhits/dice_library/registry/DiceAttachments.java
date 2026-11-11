package org._50dozenhits.dice_library.registry;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.combat.Control;
import org._50dozenhits.dice_library.dialogue.Dialogues;
import org._50dozenhits.dice_library.state.Cooldowns;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Each one says whether it is saved, kept on death and who it is synced to.
 */
public final class DiceAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTER = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DiceLib.MODID);

    /**
     * names a player has been marked with by dialogue choices and hologram buttons (UiActions flags). Saved, kept on death
     */
    public static final Supplier<AttachmentType<Set<String>>> UI_FLAGS = REGISTER.register("ui_flags",
            () -> AttachmentType.<Set<String>>builder(() -> Set.of())
                    .serialize(Codec.STRING.listOf().xmap(Set::copyOf, List::copyOf), set -> !set.isEmpty())
                    .copyOnDeath()
                    .build());

    /**
     * the dialogue tree a right-click on this entity starts (Dialogues.bind). Saved, and sent to everyone so their
     * client knows not to run the vanilla interaction
     */
    public static final Supplier<AttachmentType<ResourceLocation>> DIALOGUE = REGISTER.register("dialogue",
            () -> AttachmentType.builder(() -> Dialogues.NONE)
                    .serialize(ResourceLocation.CODEC, id -> !id.equals(Dialogues.NONE))
                    .sync((holder, to) -> true, ResourceLocation.STREAM_CODEC)
                    .build());

    /**
     * the cooldowns an entity has running (state/Cooldowns). Saved, kept on death, and a player's own sent to them
     */
    public static final Supplier<AttachmentType<Map<ResourceLocation, Cooldowns.Cooldown>>> COOLDOWNS = REGISTER.register("cooldowns",
            () -> AttachmentType.<Map<ResourceLocation, Cooldowns.Cooldown>>builder(() -> Map.of())
                    .serialize(Cooldowns.CODEC, map -> !map.isEmpty())
                    .copyOnDeath()
                    .sync((holder, to) -> holder == to, Cooldowns.STREAM_CODEC)
                    .build());

    /**
     * who controls a mob and until when (combat/Control). Saved
     */
    public static final Supplier<AttachmentType<Control.Held>> CONTROL = REGISTER.register("control",
            () -> AttachmentType.builder(() -> Control.Held.NONE)
                    .serialize(Control.Held.CODEC, held -> !held.equals(Control.Held.NONE))
                    .build());
}
