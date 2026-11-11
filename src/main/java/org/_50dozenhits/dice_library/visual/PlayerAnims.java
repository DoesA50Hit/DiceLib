package org._50dozenhits.dice_library.visual;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.state.CasterState;
import org._50dozenhits.dice_library.state.Scheduler;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Player animations (Player Animation Library) by id, shown to the player and everyone who can see them. An id like
 * mymod:sword.slash is the animation named "sword.slash" in any file under assets/mymod/player_animations/.
 * A looping animation loops until another is played or it's stopped. Start one with hold, so players
 * who come into view while it loops see it too. Server side.
 *
 * <p>play, hold and stop are the short forms. of(entity, id) sets one up with more: its speed, cues on set ticks, and
 * what ends it early.
 *
 * <pre>{@code
 * PlayerAnims.of(caster, MyMod.id("staff.channel")).looping().length(60)
 *         .at(20, entity -> pulse(entity))            // a signal 20 ticks in
 *         .stopWhenHurt().stopWhenMoving()
 *         .onEnd((entity, end) -> { if (end == PlayerAnims.End.DONE) release(entity); })
 *         .play();
 * }</pre>
 *
 * <p>Only players animate. For any other caster the animation itself is skipped, but cues, stop conditions and onEnd
 * all still run, so an ability times itself the same way whoever casts it.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class PlayerAnims {
    /**
     * ticks a new animation blends in over, and a stopped one blends out over, unless told otherwise
     */
    public static final int FADE_IN = 3, FADE_OUT = 5;
    // how far from where it started (in blocks, along the ground) counts as having moved
    private static final double MOVED = 0.5;

    /**
     * Why an animation set up with of(...) stopped
     */
    public enum End {
        /**
         * its length(ticks) ran out
         */
        DONE,
        /**
         * stop was called
         */
        STOPPED,
        /**
         * another animation was played on the same entity
         */
        REPLACED,
        /**
         * stopWhenHurt: the entity took damage
         */
        HURT,
        /**
         * stopWhenMoving: the entity moved from where it started
         */
        MOVED,
        /**
         * stopOnItemSwap: the entity's main hand holds another item
         */
        SWAPPED,
        /**
         * stopWhen: the condition held
         */
        CONDITION,
        /**
         * the entity died or left the level
         */
        GONE
    }

    // the looping animation each player is held in, for whoever starts seeing them later
    private static final Map<UUID, PlayerAnimPayload> HELD = new HashMap<>();
    // what each entity is playing, when it was set up with of(...) or is a held loop
    private static final CasterState<Playing> PLAYING = CasterState.create((id, playing) -> {
        HELD.remove(id);
        playing.finish(End.GONE);
    });

    public static void play(LivingEntity entity, ResourceLocation id) {
        of(entity, id).play();
    }

    /**
     * Plays a looping animation and keeps it: a player who comes into view later (walks up, logs in) is shown it too,
     * where play only reaches who is watching now. It lasts until play or stop.
     */
    public static void hold(LivingEntity entity, ResourceLocation id) {
        of(entity, id).looping().play();
    }

    /**
     * ends whatever DiceLib's layer is playing on the entity, easing back to standing over FADE_OUT ticks
     */
    public static void stop(LivingEntity entity) {
        stop(entity, FADE_OUT);
    }

    /**
     * the same, easing out over {@code fadeOut} ticks (0 snaps back)
     */
    public static void stop(LivingEntity entity, int fadeOut) {
        Playing playing = PLAYING.take(entity);
        // the stop goes out first, so an onEnd that plays the next animation isn't cut off by it
        sendStop(entity, fadeOut);
        if (playing != null) playing.finish(End.STOPPED);
    }

    /**
     * what the entity is playing now, when it was started with of(...).play(), play or hold and hasn't stopped. Null
     * otherwise. An animation with no length, no cues and no stop conditions is forgotten as soon as it is sent,
     * unless it loops.
     */
    @Nullable
    public static Playing playing(LivingEntity entity) {
        return PLAYING.get(entity);
    }

    /**
     * An animation to set up. play() starts it.
     */
    public static Anim of(LivingEntity entity, ResourceLocation id) {
        return new Anim(entity, id);
    }

    public static final class Anim {
        private final LivingEntity entity;
        private final ResourceLocation id;
        private float speed = 1;
        private int fadeIn = FADE_IN;
        private boolean mirrored;
        private boolean looping;
        private int length;
        private final List<Cue> cues = new ArrayList<>();
        private boolean whenHurt, whenMoving, onSwap;
        @Nullable
        private Predicate<LivingEntity> stopWhen;
        @Nullable
        private BiConsumer<LivingEntity, End> onEnd;

        private record Cue(int tick, Consumer<LivingEntity> action) {
        }

        private Anim(LivingEntity entity, ResourceLocation id) {
            this.entity = entity;
            this.id = id;
        }

        /**
         * plays it this many times as fast (2 is double speed, 0.5 half). Cues and length stay in server ticks, so
         * scale them yourself.
         */
        public Anim speed(float factor) {
            this.speed = Math.max(0.01f, factor);
            return this;
        }

        /**
         * ticks it blends in over (3 by default, 0 snaps)
         */
        public Anim fadeIn(int ticks) {
            this.fadeIn = Math.max(0, ticks);
            return this;
        }

        /**
         * swaps left and right, so one animation serves both hands
         */
        public Anim mirrored() {
            this.mirrored = true;
            return this;
        }

        /**
         * for an animation whose file loops: players who come into view while it plays are shown it too
         */
        public Anim looping() {
            this.looping = true;
            return this;
        }

        /**
         * How long the server counts it as playing, in ticks. The server can't read animation files, so without this
         * it never reports DONE. When the time is up a looping animation is stopped. Any other has ended on its own.
         */
        public Anim length(int ticks) {
            this.length = Math.max(0, ticks);
            return this;
        }

        /**
         * Runs on the server {@code tick} ticks after it starts (0 is immediately), if it is still playing then. Use it
         * for the moment a swing connects.
         */
        public Anim at(int tick, Consumer<LivingEntity> action) {
            cues.add(new Cue(Math.max(0, tick), action));
            return this;
        }

        /**
         * ends it when the entity takes damage
         */
        public Anim stopWhenHurt() {
            this.whenHurt = true;
            return this;
        }

        /**
         * ends it once the entity is more than half a block along the ground from where it started
         */
        public Anim stopWhenMoving() {
            this.whenMoving = true;
            return this;
        }

        /**
         * ends it when the item in the entity's main hand is no longer the one it started with
         */
        public Anim stopOnItemSwap() {
            this.onSwap = true;
            return this;
        }

        /**
         * ends it on the first tick this holds
         */
        public Anim stopWhen(Predicate<LivingEntity> condition) {
            this.stopWhen = condition;
            return this;
        }

        /**
         * Runs once however it stops, with the reason. Use it to undo whatever the cast granted, or to release
         * the spell on DONE.
         */
        public Anim onEnd(BiConsumer<LivingEntity, End> onEnd) {
            this.onEnd = onEnd;
            return this;
        }

        /**
         * Starts it, replacing whatever the entity was playing.
         */
        public Playing play() {
            Playing old = PLAYING.take(entity);
            if (old != null) old.finish(End.REPLACED);
            Playing playing = new Playing(this);
            PlayerAnimPayload payload = new PlayerAnimPayload(entity.getId(), Optional.of(id), speed, fadeIn, mirrored);
            if (entity instanceof ServerPlayer player) {
                if (looping) HELD.put(player.getUUID(), payload);
                else HELD.remove(player.getUUID());
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload);
            }
            for (Cue cue : cues) if (cue.tick() == 0) cue.action().accept(entity);
            boolean tracked = looping || length > 0 || whenHurt || whenMoving || onSwap || stopWhen != null || onEnd != null
                    || cues.stream().anyMatch(cue -> cue.tick() > 0);
            if (!tracked) {
                playing.over = true;
                return playing;
            }
            PLAYING.put(entity, playing);
            if (length > 0 || whenMoving || onSwap || stopWhen != null || cues.stream().anyMatch(cue -> cue.tick() > 0)) {
                playing.task = Scheduler.repeat(entity, 1, Integer.MAX_VALUE, (ticking, run) -> playing.tick(run + 1));
            }
            return playing;
        }
    }

    /**
     * An animation that was started. Server side.
     */
    public static final class Playing {
        private final Anim anim;
        private final Vec3 startedAt;
        private final ItemStack startedWith;
        private int tick;
        private boolean over;
        @Nullable
        private Scheduler.Task task;

        private Playing(Anim anim) {
            this.anim = anim;
            this.startedAt = anim.entity.position();
            this.startedWith = anim.entity.getMainHandItem().copy();
        }

        public ResourceLocation id() {
            return anim.id;
        }

        public LivingEntity entity() {
            return anim.entity;
        }

        /**
         * server ticks since it started
         */
        public int tick() {
            return tick;
        }

        public boolean isPlaying() {
            return !over;
        }

        /**
         * stops it, if it is still the one playing
         */
        public void stop() {
            if (!over && PLAYING.get(anim.entity) == this) PlayerAnims.stop(anim.entity);
        }

        private void tick(int now) {
            if (over) return;
            tick = now;
            LivingEntity entity = anim.entity;
            End end = null;
            if (anim.whenMoving && entity.position().subtract(startedAt).horizontalDistanceSqr() > MOVED * MOVED) end = End.MOVED;
            else if (anim.onSwap && !ItemStack.isSameItem(entity.getMainHandItem(), startedWith)) end = End.SWAPPED;
            else if (anim.stopWhen != null && anim.stopWhen.test(entity)) end = End.CONDITION;
            if (end != null) {
                end(end, true);
                return;
            }
            for (Anim.Cue cue : anim.cues) {
                if (cue.tick() == now) cue.action().accept(entity);
                if (over) return;
            }
            // an animation that doesn't loop has run out on the clients already
            if (anim.length > 0 && now >= anim.length) end(End.DONE, anim.looping);
        }

        // ends it as the one playing, and eases the player out of it when asked
        private void end(End reason, boolean tellClients) {
            if (PLAYING.get(anim.entity) == this) PLAYING.take(anim.entity);
            if (tellClients) sendStop(anim.entity, FADE_OUT);
            finish(reason);
        }

        private void finish(End reason) {
            if (over) return;
            over = true;
            if (task != null) task.cancel();
            if (anim.onEnd != null) anim.onEnd.accept(anim.entity, reason);
        }
    }

    private static void sendStop(LivingEntity entity, int fadeOut) {
        if (!(entity instanceof ServerPlayer player)) return;
        HELD.remove(player.getUUID());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new PlayerAnimPayload(player.getId(), Optional.empty(), 1, Math.max(0, fadeOut), false));
    }

    @SubscribeEvent
    static void onDamage(LivingDamageEvent.Post event) {
        Playing playing = PLAYING.get(event.getEntity());
        if (playing != null && playing.anim.whenHurt && event.getNewDamage() > 0) playing.end(End.HURT, true);
    }

    @SubscribeEvent
    static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer seen) || !(event.getEntity() instanceof ServerPlayer viewer)) return;
        PlayerAnimPayload held = HELD.get(seen.getUUID());
        if (held != null) PacketDistributor.sendToPlayer(viewer, held);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        HELD.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onStopped(ServerStoppedEvent event) {
        HELD.clear();
    }

    /**
     * An empty id stops, easing out over {@code fade} ticks. Otherwise {@code fade} is the blend in.
     */
    public record PlayerAnimPayload(int entity, Optional<ResourceLocation> id, float speed, int fade, boolean mirrored) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<PlayerAnimPayload> TYPE = new Type<>(DiceLib.id("player_anim"));

        @ApiStatus.Internal
        public static final StreamCodec<ByteBuf, PlayerAnimPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PlayerAnimPayload::entity,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), PlayerAnimPayload::id,
                ByteBufCodecs.FLOAT, PlayerAnimPayload::speed,
                ByteBufCodecs.VAR_INT, PlayerAnimPayload::fade,
                ByteBufCodecs.BOOL, PlayerAnimPayload::mirrored,
                PlayerAnimPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
