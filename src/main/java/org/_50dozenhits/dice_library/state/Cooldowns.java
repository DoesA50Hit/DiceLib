package org._50dozenhits.dice_library.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org._50dozenhits.dice_library.registry.DiceAttachments;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Cooldowns by id, on any entity. Use one id per ability or item, or share an id between several. They are saved
 * with the entity, kept through death, and a player's own are sent to their client, so a HUD can read remaining and
 * progress there with the same calls. Counted in game time, so they stand still while the server is down. Starting and
 * clearing are server side. Reading works on both.
 *
 * <pre>{@code
 * if (!Cooldowns.tryStart(caster, MyMod.id("fireball"), 100)) return;    // not ready yet
 * float filled = Cooldowns.progress(player, MyMod.id("fireball"), partialTick);   // 0 just used, 1 ready: for a HUD
 * }</pre>
 */
public final class Cooldowns {
    /**
     * game times: when it started and when it is ready again
     */
    public record Cooldown(long start, long end) {
        public static final Codec<Cooldown> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("start").forGetter(Cooldown::start),
                Codec.LONG.fieldOf("end").forGetter(Cooldown::end)
        ).apply(i, Cooldown::new));
        @ApiStatus.Internal
        public static final StreamCodec<ByteBuf, Cooldown> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Cooldown::start, ByteBufCodecs.VAR_LONG, Cooldown::end, Cooldown::new);
    }

    @ApiStatus.Internal
    public static final Codec<Map<ResourceLocation, Cooldown>> CODEC = Codec.unboundedMap(ResourceLocation.CODEC, Cooldown.CODEC);
    @ApiStatus.Internal
    public static final StreamCodec<ByteBuf, Map<ResourceLocation, Cooldown>> STREAM_CODEC =
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, Cooldown.STREAM_CODEC);

    /**
     * Starts the cooldown, {@code ticks} long from now, replacing one already running. 0 or less clears it.
     */
    public static void start(Entity entity, ResourceLocation id, int ticks) {
        if (entity.level().isClientSide()) return;
        if (ticks <= 0) {
            clear(entity, id);
            return;
        }
        long now = entity.level().getGameTime();
        Map<ResourceLocation, Cooldown> next = running(entity, now);
        next.put(id, new Cooldown(now, now + ticks));
        entity.setData(DiceAttachments.COOLDOWNS, Map.copyOf(next));
    }

    /**
     * Starts it when it is ready and returns true. False, and nothing changes, while it is still running. The usual
     * first line of an ability.
     */
    public static boolean tryStart(Entity entity, ResourceLocation id, int ticks) {
        if (entity.level().isClientSide() || !ready(entity, id)) return false;
        start(entity, id, ticks);
        return true;
    }

    public static boolean ready(Entity entity, ResourceLocation id) {
        return remaining(entity, id) <= 0;
    }

    /**
     * ticks until it is ready, 0 when it is
     */
    public static int remaining(Entity entity, ResourceLocation id) {
        Cooldown cooldown = get(entity, id);
        return cooldown == null ? 0 : (int) Math.max(0, cooldown.end() - entity.level().getGameTime());
    }

    /**
     * How far through it is, from 0 the moment it starts to 1 when ready (and 1 for none running). {@code partialTick}
     * smooths it between ticks for drawing. Pass 0 anywhere else.
     */
    public static float progress(Entity entity, ResourceLocation id, float partialTick) {
        Cooldown cooldown = get(entity, id);
        if (cooldown == null || cooldown.end() <= cooldown.start()) return 1;
        float through = (entity.level().getGameTime() + partialTick - cooldown.start()) / (cooldown.end() - cooldown.start());
        return Mth.clamp(through, 0, 1);
    }

    /**
     * Makes it ready {@code ticks} sooner (a refund on a kill, haste). Its start moves with it, so progress stays smooth.
     */
    public static void reduce(Entity entity, ResourceLocation id, int ticks) {
        if (entity.level().isClientSide() || ticks <= 0) return;
        long now = entity.level().getGameTime();
        Map<ResourceLocation, Cooldown> next = running(entity, now);
        Cooldown cooldown = next.get(id);
        if (cooldown == null) return;
        if (cooldown.end() - ticks <= now) next.remove(id);
        else next.put(id, new Cooldown(cooldown.start() - ticks, cooldown.end() - ticks));
        entity.setData(DiceAttachments.COOLDOWNS, Map.copyOf(next));
    }

    public static void clear(Entity entity, ResourceLocation id) {
        if (entity.level().isClientSide()) return;
        Map<ResourceLocation, Cooldown> next = running(entity, entity.level().getGameTime());
        if (!entity.getData(DiceAttachments.COOLDOWNS).containsKey(id)) return;
        next.remove(id);
        entity.setData(DiceAttachments.COOLDOWNS, Map.copyOf(next));
    }

    /**
     * every cooldown the entity has, for a respec
     */
    public static void clearAll(Entity entity) {
        if (entity.level().isClientSide() || entity.getData(DiceAttachments.COOLDOWNS).isEmpty()) return;
        entity.setData(DiceAttachments.COOLDOWNS, Map.of());
    }

    /**
     * the ones still running, by id
     */
    public static Map<ResourceLocation, Cooldown> all(Entity entity) {
        return Map.copyOf(running(entity, entity.level().getGameTime()));
    }

    @Nullable
    private static Cooldown get(Entity entity, ResourceLocation id) {
        return entity.getData(DiceAttachments.COOLDOWNS).get(id);
    }

    // a copy to change, with the ones that ran out left behind
    private static Map<ResourceLocation, Cooldown> running(Entity entity, long now) {
        Map<ResourceLocation, Cooldown> running = new HashMap<>();
        entity.getData(DiceAttachments.COOLDOWNS).forEach((id, cooldown) -> {
            if (cooldown.end() > now) running.put(id, cooldown);
        });
        return running;
    }

    private Cooldowns() {
    }
}
