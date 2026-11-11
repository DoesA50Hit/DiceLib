package org._50dozenhits.dice_library.visual;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.state.Scheduler;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effects by id, played on every client within RANGE blocks. Each client hands the id to the effect players its mods
 * added (ClientFx.addPlayer), so one backed by an effect library can play mymod:spell/burst from its own file. When no
 * player plays it, the client draws the Fallback instead, a vanilla particle pattern, so content works before its
 * effect file is made and with no effect library at all. Every effect is one-shot: a zone or aura that lasts plays a
 * short effect again each pulse. Server side.
 */
public final class Fx {
    public static final double RANGE = 64;
    private static final Set<ResourceLocation> HIDDEN_ON_YOURSELF = ConcurrentHashMap.newKeySet();

    /**
     * Effects played on an entity (Fx.on) that would fill your screen when they are around you: a shell, a dome, a
     * glitch tear. Your own client skips them while you look in first person. Everyone else still sees them. Call it
     * once at startup, from either side (common setup, a static block).
     */
    public static void hideOnYourself(ResourceLocation id) {
        HIDDEN_ON_YOURSELF.add(id);
    }

    public static boolean isHiddenOnYourself(ResourceLocation id) {
        return HIDDEN_ON_YOURSELF.contains(id);
    }

    public enum Pattern {
        /**
         * {@code count} particles scattered {@code size} blocks around the point
         */
        BURST,
        /**
         * a flat circle of radius {@code size}
         */
        RING,
        /**
         * the surface of a ball of radius {@code size}
         */
        SPHERE,
        /**
         * straight up, {@code size} blocks tall
         */
        COLUMN,
        /**
         * from the point to the line's end
         */
        LINE
    }

    /**
     * What a client without the effect file draws.
     */
    public record Fallback(ParticleOptions particle, Pattern pattern, float size, int count) {
        public static Fallback burst(ParticleOptions particle, float spread, int count) {
            return new Fallback(particle, Pattern.BURST, spread, count);
        }

        public static Fallback ring(ParticleOptions particle, float radius) {
            return new Fallback(particle, Pattern.RING, radius, Math.max(8, Math.round(radius * 8)));
        }

        public static Fallback sphere(ParticleOptions particle, float radius) {
            return new Fallback(particle, Pattern.SPHERE, radius, Math.max(12, Math.round(radius * radius * 6)));
        }

        public static Fallback column(ParticleOptions particle, float height) {
            return new Fallback(particle, Pattern.COLUMN, height, Math.max(6, Math.round(height * 4)));
        }

        public static Fallback line(ParticleOptions particle) {
            return new Fallback(particle, Pattern.LINE, 0, 0);
        }
    }

    /**
     * At a point. {@code scale} stretches the effect evenly, so a zone's effect made 1 block wide is played at its radius.
     */
    public static void at(ServerLevel level, ResourceLocation id, Vec3 pos, float scale, Fallback fallback) {
        send(level, new FxPayload(id, -1, pos, pos, scale, Float.NaN, fallback));
    }

    /**
     * The same, turned to face {@code yaw} (degrees, as an entity's): the effect is made facing +Z.
     */
    public static void at(ServerLevel level, ResourceLocation id, Vec3 pos, float scale, float yaw, Fallback fallback) {
        send(level, new FxPayload(id, -1, pos, pos, scale, yaw, fallback));
    }

    /**
     * On an entity's feet, following it while the effect plays.
     */
    public static void on(Entity entity, ResourceLocation id, float scale, Fallback fallback) {
        if (entity.level() instanceof ServerLevel level) send(level, new FxPayload(id, entity.getId(), entity.position(), entity.position(), scale, Float.NaN, fallback));
    }

    /**
     * The same, played again every {@code every} ticks for {@code ticks}, for something that lasts (a mark, an aura). Stops
     * early when the entity dies or is removed. Cancel the task to stop it sooner.
     */
    public static Scheduler.Task onFor(Entity entity, ResourceLocation id, float scale, Fallback fallback, int every, int ticks) {
        int interval = Math.max(1, every);
        on(entity, id, scale, fallback);
        return Scheduler.repeat(entity, interval, Math.max(0, ticks / interval), (on, run) -> on(on, id, scale, fallback));
    }

    /**
     * A sound at a point, for everyone near enough to hear it, as a player's sound (SoundSource.PLAYERS).
     */
    public static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        sound(level, at, sound, SoundSource.PLAYERS, volume, pitch);
    }

    public static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, float volume, float pitch) {
        sound(level, at, sound, SoundSource.PLAYERS, volume, pitch);
    }

    /**
     * The same on another volume slider: for example HOSTILE for a monster's ability.
     */
    public static void sound(ServerLevel level, Vec3 at, SoundEvent sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch);
    }

    public static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch);
    }

    /**
     * at an entity's feet. Nothing on the client
     */
    public static void sound(Entity at, SoundEvent sound, float volume, float pitch) {
        if (at.level() instanceof ServerLevel level) sound(level, at.position(), sound, volume, pitch);
    }

    public static void sound(Entity at, Holder<SoundEvent> sound, float volume, float pitch) {
        if (at.level() instanceof ServerLevel level) sound(level, at.position(), sound, volume, pitch);
    }

    /**
     * at an entity's feet, on {@code source}'s slider (an entity's own is at.getSoundSource())
     */
    public static void sound(Entity at, SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (at.level() instanceof ServerLevel level) sound(level, at.position(), sound, source, volume, pitch);
    }

    public static void sound(Entity at, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
        if (at.level() instanceof ServerLevel level) sound(level, at.position(), sound, source, volume, pitch);
    }

    /**
     * Something thrown from {@code from} to {@code to} over {@code ticks}, rising {@code arc} blocks in the middle. The effect {@code id} (made 1 block
     * long along +X, facing the way it flies, like a line effect) is played where it is each tick, and onArrive runs
     * on the tick it lands. Use a short effect (a streak, a glint): each tick plays a new copy. One packet starts the
     * flight and each client steps it, so a long throw costs no more than a short one. For a thrown entity with an
     * effect that follows it, use on(entity, ...) instead.
     */
    public static void fly(ServerLevel level, ResourceLocation id, Vec3 from, Vec3 to, int ticks, double arc, Fallback fallback,
                           Runnable onArrive) {
        int total = Math.max(1, ticks);
        Vec3 middle = from.lerp(to, 0.5);
        double reach = RANGE + from.distanceTo(to) / 2;
        PacketDistributor.sendToPlayersNear(level, null, middle.x, middle.y, middle.z, reach,
                new FxFlightPayload(id, from, to, total, (float) arc, fallback));
        Scheduler.after(total, onArrive);
    }

    /**
     * the ticks something moving {@code speed} blocks a tick takes from {@code from} to {@code to}, at least 1
     */
    public static int travel(Vec3 from, Vec3 to, double speed) {
        return Math.max(1, (int) Math.ceil(from.distanceTo(to) / speed));
    }

    /**
     * From {@code from} to {@code to}: the effect is made 1 block long along +X and is turned and stretched to fit.
     */
    public static void line(ServerLevel level, ResourceLocation id, Vec3 from, Vec3 to, Fallback fallback) {
        line(level, id, from, to, 1, fallback);
    }

    /**
     * The same, {@code thickness} times as thick. A line 1 block long turns an effect to face along it without stretching it,
     * for a circle that faces where someone looks.
     */
    public static void line(ServerLevel level, ResourceLocation id, Vec3 from, Vec3 to, float thickness, Fallback fallback) {
        send(level, new FxPayload(id, -1, from, to, thickness, Float.NaN, fallback));
    }

    private static void send(ServerLevel level, FxPayload payload) {
        PacketDistributor.sendToPlayersNear(level, null, payload.from().x, payload.from().y, payload.from().z, RANGE, payload);
    }

    /**
     * entity -1 for one at a point. {@code to} equals {@code from} unless it's a line. yaw is NaN for no turn
     */
    public record FxPayload(ResourceLocation id, int entity, Vec3 from, Vec3 to, float scale, float yaw, Fallback fallback) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<FxPayload> TYPE = new Type<>(DiceLib.id("fx"));

        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> STREAM_CODEC = StreamCodec.of(FxPayload::write, FxPayload::read);

        private static void write(RegistryFriendlyByteBuf buf, FxPayload payload) {
            buf.writeResourceLocation(payload.id);
            buf.writeVarInt(payload.entity);
            writeVec(buf, payload.from);
            writeVec(buf, payload.to);
            buf.writeFloat(payload.scale);
            buf.writeFloat(payload.yaw);
            writeFallback(buf, payload.fallback);
        }

        private static FxPayload read(RegistryFriendlyByteBuf buf) {
            ResourceLocation id = buf.readResourceLocation();
            int entity = buf.readVarInt();
            Vec3 from = readVec(buf), to = readVec(buf);
            float scale = buf.readFloat();
            float yaw = buf.readFloat();
            return new FxPayload(id, entity, from, to, scale, yaw, readFallback(buf));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A thrown effect (fly): each client plays step(0) immediately and one more step every tick, up to step(ticks).
     */
    public record FxFlightPayload(ResourceLocation id, Vec3 from, Vec3 to, int ticks, float arc, Fallback fallback) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<FxFlightPayload> TYPE = new Type<>(DiceLib.id("fx_flight"));

        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, FxFlightPayload> STREAM_CODEC = StreamCodec.of(FxFlightPayload::write, FxFlightPayload::read);

        /**
         * where it is at {@code t}, 0 to 1 of the way
         */
        public Vec3 point(double t) {
            return from.lerp(to, t).add(0, arc * 4 * t * (1 - t), 0);
        }

        /**
         * The line effect for tick {@code run}: from where it is to a block ahead along the way it flies.
         */
        public FxPayload step(int run) {
            double t = run / (double) ticks, next = Math.min(1, (run + 1) / (double) ticks);
            Vec3 at = point(t);
            Vec3 face = point(next).subtract(at);
            if (face.lengthSqr() < 1.0E-6) face = to.subtract(from);
            Vec3 tip = at.add(face.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : face.normalize());
            return new FxPayload(id, -1, at, tip, 1, Float.NaN, fallback);
        }

        private static void write(RegistryFriendlyByteBuf buf, FxFlightPayload payload) {
            buf.writeResourceLocation(payload.id);
            writeVec(buf, payload.from);
            writeVec(buf, payload.to);
            buf.writeVarInt(payload.ticks);
            buf.writeFloat(payload.arc);
            writeFallback(buf, payload.fallback);
        }

        private static FxFlightPayload read(RegistryFriendlyByteBuf buf) {
            ResourceLocation id = buf.readResourceLocation();
            Vec3 from = readVec(buf), to = readVec(buf);
            int ticks = Math.max(1, buf.readVarInt());
            float arc = buf.readFloat();
            return new FxFlightPayload(id, from, to, ticks, arc, readFallback(buf));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeFallback(RegistryFriendlyByteBuf buf, Fallback fallback) {
        ParticleTypes.STREAM_CODEC.encode(buf, fallback.particle());
        buf.writeByte(fallback.pattern().ordinal());
        buf.writeFloat(fallback.size());
        buf.writeVarInt(fallback.count());
    }

    private static Fallback readFallback(RegistryFriendlyByteBuf buf) {
        ParticleOptions particle = ParticleTypes.STREAM_CODEC.decode(buf);
        Pattern pattern = Pattern.values()[buf.readByte()];
        return new Fallback(particle, pattern, buf.readFloat(), buf.readVarInt());
    }

    private static void writeVec(RegistryFriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vec3 readVec(RegistryFriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
