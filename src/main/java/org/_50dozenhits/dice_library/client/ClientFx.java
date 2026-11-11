package org._50dozenhits.dice_library.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.visual.Fx;
import org._50dozenhits.dice_library.visual.Fx.Fallback;
import org._50dozenhits.dice_library.visual.Fx.FxFlightPayload;
import org._50dozenhits.dice_library.visual.Fx.FxPayload;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays what Fx sends. DiceLib itself draws no effect files: each effect player added with addPlayer gets a try,
 * in the order they were added, and the first one that plays the effect wins. When none does (no player added, or no
 * file by that id), the effect's vanilla fallback particles play instead. A mod that uses an effect library (Photon,
 * for one) adds a player for it once at client setup:
 *
 * <pre>{@code
 * ClientFx.addPlayer((level, entity, payload) -> MyEffects.has(payload.id()) && MyEffects.play(level, entity, payload));
 * }</pre>
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class ClientFx {
    @FunctionalInterface
    public interface FxPlayer {
        // Plays the effect and returns true, or returns false to pass it on. `entity` is the entity it plays on, or
        // null for one at payload.from() (stretched to payload.to() for a line).
        boolean play(ClientLevel level, @Nullable Entity entity, FxPayload payload);
    }

    private static final List<FxPlayer> PLAYERS = new CopyOnWriteArrayList<>();
    // thrown effects in the air, stepped every client tick. Only touched on the main thread
    private static final List<Flight> FLIGHTS = new ArrayList<>();

    public static void addPlayer(FxPlayer player) {
        PLAYERS.add(player);
    }

    @ApiStatus.Internal
    public static void accept(FxPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) play(level, payload);
    }

    /**
     * Starts a thrown effect: its first step now, one more each tick until it lands.
     */
    @ApiStatus.Internal
    public static void accept(FxFlightPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        play(level, payload.step(0));
        FLIGHTS.add(new Flight(payload, level));
    }

    private static void play(ClientLevel level, FxPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = payload.entity() >= 0 ? level.getEntity(payload.entity()) : null;
        if (payload.entity() >= 0 && entity == null) return;
        if (entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson() && Fx.isHiddenOnYourself(payload.id())) return;
        for (FxPlayer player : PLAYERS) {
            if (player.play(level, entity, payload)) return;
        }
        fallback(level, entity, payload);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (FLIGHTS.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        // a flight belongs to the level it started in. A paused game holds it where it is
        FLIGHTS.removeIf(flight -> flight.level != minecraft.level);
        if (minecraft.isPaused()) return;
        FLIGHTS.removeIf(flight -> {
            flight.run++;
            play(flight.level, flight.payload.step(flight.run));
            return flight.run >= flight.payload.ticks();
        });
    }

    private static final class Flight {
        private final FxFlightPayload payload;
        private final ClientLevel level;
        private int run;

        private Flight(FxFlightPayload payload, ClientLevel level) {
            this.payload = payload;
            this.level = level;
        }
    }

    private static void fallback(ClientLevel level, Entity entity, FxPayload payload) {
        Fallback fallback = payload.fallback();
        RandomSource random = level.random;
        Vec3 at = entity != null ? entity.position() : payload.from();
        // the fallback's size is already in blocks. Scale only stretches an effect player's effect
        float size = fallback.size();
        int count = fallback.count();
        switch (fallback.pattern()) {
            case BURST -> {
                double y = entity != null ? at.y + entity.getBbHeight() / 2 : at.y;
                for (int i = 0; i < count; i++) {
                    level.addParticle(fallback.particle(), at.x + random.nextGaussian() * size, y + random.nextGaussian() * size,
                            at.z + random.nextGaussian() * size, random.nextGaussian() * 0.02, random.nextGaussian() * 0.02, random.nextGaussian() * 0.02);
                }
            }
            case RING -> {
                for (int i = 0; i < count; i++) {
                    double angle = Mth.TWO_PI * i / count;
                    level.addParticle(fallback.particle(), at.x + Math.cos(angle) * size, at.y + 0.1, at.z + Math.sin(angle) * size, 0, 0.01, 0);
                }
            }
            case SPHERE -> {
                for (int i = 0; i < count; i++) {
                    Vec3 point = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().scale(size);
                    level.addParticle(fallback.particle(), at.x + point.x, at.y + point.y, at.z + point.z, 0, 0, 0);
                }
            }
            case COLUMN -> {
                for (int i = 0; i < count; i++) {
                    level.addParticle(fallback.particle(), at.x, at.y + size * i / count, at.z, 0, 0.02, 0);
                }
            }
            case LINE -> {
                Vec3 to = payload.to();
                int steps = Math.max(2, (int) (to.distanceTo(at) * 3));
                for (int i = 0; i <= steps; i++) {
                    Vec3 point = at.lerp(to, (double) i / steps);
                    level.addParticle(fallback.particle(), point.x, point.y, point.z, 0, 0, 0);
                }
            }
        }
    }
}
