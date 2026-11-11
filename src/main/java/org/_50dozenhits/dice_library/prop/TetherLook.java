package org._50dozenhits.dice_library.prop;

import net.minecraft.resources.ResourceLocation;
import org._50dozenhits.dice_library.DiceLib;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a Tether is drawn, registered by id so both sides agree on it. Three kinds:
 * - LINKS: flat oval links, each turned a quarter round from the one before like a real chain (texture: one link)
 * - GLYPHS: a string of flat signs, each drawn both ways round so it reads from any side (texture: {@code cells} signs side
 * <pre>{@code
 * by side, used in turn)
 * }</pre>
 * - RIBBON: one smooth band, two crossed strips so it reads from any side, its texture scrolling along it with the
 * <pre>{@code
 * tether's flow, swelling in pulses and narrowing to a point at the far end (width and tint come from the tether)
 * }</pre>
 * Register yours once at common setup (a static block is fine):
 * <pre>{@code
 * public static final TetherLook THORNS = TetherLook.register(TetherLook.links(MyMod.id("thorns"),
 *         MyMod.id("textures/entity/thorns.png"), 0.26f, 0.4f, 0.28f));
 * }</pre>
 */
public record TetherLook(ResourceLocation id, Kind kind, ResourceLocation texture, float width, float linkLength,
                         float spacing, int cells, int minLight, @Nullable ResourceLocation breakFx) {
    public enum Kind {
        LINKS,
        GLYPHS,
        RIBBON
    }

    private static final Map<ResourceLocation, TetherLook> LOOKS = new ConcurrentHashMap<>();

    /**
     * the framework's own: an iron chain and a red ribbon (tint it white-to-anything with the tether's ribbon(...))
     */
    public static final TetherLook CHAIN = register(links(DiceLib.id("chain"), DiceLib.id("textures/tether/chain.png"), 0.24f, 0.42f, 0.3f));
    public static final TetherLook RIBBON = register(ribbon(DiceLib.id("ribbon"), DiceLib.id("textures/tether/ribbon.png")));

    /**
     * links {@code width} across and {@code linkLength} long, one every {@code spacing} blocks
     */
    public static TetherLook links(ResourceLocation id, ResourceLocation texture, float width, float linkLength, float spacing) {
        return new TetherLook(id, Kind.LINKS, texture, width, linkLength, spacing, 1, 4, null);
    }

    /**
     * signs {@code width} across and {@code length} long, one every {@code spacing} blocks, from a texture of {@code cells} side by side
     */
    public static TetherLook glyphs(ResourceLocation id, ResourceLocation texture, float width, float length, float spacing, int cells) {
        return new TetherLook(id, Kind.GLYPHS, texture, width, length, spacing, Math.max(1, cells), 9, null);
    }

    public static TetherLook ribbon(ResourceLocation id, ResourceLocation texture) {
        return new TetherLook(id, Kind.RIBBON, texture, 0.25f, 0, 0, 1, 11, null);
    }

    /**
     * never drawn darker than this block light (0-15), so dark chains still read at night
     */
    public TetherLook minLight(int minLight) {
        return new TetherLook(id, kind, texture, width, linkLength, spacing, cells, minLight, breakFx);
    }

    /**
     * the Photon effect played where a breakable tether breaks
     */
    public TetherLook breakFx(ResourceLocation breakFx) {
        return new TetherLook(id, kind, texture, width, linkLength, spacing, cells, minLight, breakFx);
    }

    public static TetherLook register(TetherLook look) {
        LOOKS.put(look.id(), look);
        return look;
    }

    /**
     * the look by id, or CHAIN for one nobody registered
     */
    public static TetherLook get(ResourceLocation id) {
        return LOOKS.getOrDefault(id, CHAIN);
    }
}
