package org._50dozenhits.dice_library.dialogue;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.ui.Audience;
import org._50dozenhits.dice_library.ui.UiActions;
import org._50dozenhits.dice_library.ui.UiLooks;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Battle lines: a subtitle above the hotbar of every player in range, with the speaker's name, an optional portrait,
 * and text that types itself out. A speaker entity can also get a speech bubble over its head. Lines queue on each
 * client. interrupt() replaces whatever is showing. Server side:
 *
 * <pre>{@code
 * Speech.of(boss, Component.literal("Kneel.")).range(32).bubble().voice(SoundEvents.WITHER_AMBIENT, 1.4f).send();
 * Speech.of(Component.literal("A voice"), Component.literal("Turn back.")).at(level, altar).range(16).send();
 * Speech.of(npc, line).to(players).send();          // exactly these players, range and audience ignored
 * }</pre>
 */
public final class Speech {
    public static final int DEFAULT_RANGE = 32;

    /**
     * a line said by an entity: its name, its position for range, and a bubble over its head if asked for
     */
    public static Line of(Entity speaker, Component text) {
        return new Line(speaker, speaker.getDisplayName(), text);
    }

    /**
     * a line with no entity behind it. Needs at(level, pos) or to(players)
     */
    public static Line of(Component name, Component text) {
        return new Line(null, name, text);
    }

    public static final class Line {
        @Nullable
        private final Entity speaker;
        private Component name;
        private final Component text;
        @Nullable
        private ServerLevel level;
        @Nullable
        private Vec3 origin;
        private double range = DEFAULT_RANGE;
        @Nullable
        private Collection<ServerPlayer> to;
        private Audience audience = Audience.everyone();
        private boolean bubble;
        private int ticks = -1;
        private ItemStack portrait = ItemStack.EMPTY;
        private boolean speakerPortrait;
        @Nullable
        private Holder<SoundEvent> voice;
        private float pitch = 1;
        private boolean interrupt;
        private ResourceLocation style = UiLooks.DEFAULT;

        private Line(@Nullable Entity speaker, Component name, Component text) {
            this.speaker = speaker;
            this.name = name;
            this.text = text;
            if (speaker != null && speaker.level() instanceof ServerLevel server) {
                this.level = server;
                this.origin = speaker.position();
            }
        }

        /**
         * the name shown, instead of the speaker's own
         */
        public Line name(Component name) {
            this.name = name;
            return this;
        }

        /**
         * where a line with no speaker is heard from
         */
        public Line at(ServerLevel level, Vec3 pos) {
            this.level = level;
            this.origin = pos;
            return this;
        }

        /**
         * blocks from the speaker (or at()) that the line reaches. 32 by default
         */
        public Line range(double range) {
            this.range = range;
            return this;
        }

        /**
         * exactly these players, whatever the range and audience
         */
        public Line to(Collection<ServerPlayer> players) {
            this.to = List.copyOf(players);
            return this;
        }

        public Line to(ServerPlayer... players) {
            return to(List.of(players));
        }

        /**
         * only players in range this audience passes
         */
        public Line audience(Audience audience) {
            this.audience = audience;
            return this;
        }

        /**
         * a speech bubble over the speaker's head too (players can turn bubbles off)
         */
        public Line bubble() {
            this.bubble = true;
            return this;
        }

        /**
         * How long the line stays once it has typed out. By default 40 ticks plus one per character.
         */
        public Line ticks(int ticks) {
            this.ticks = ticks;
            return this;
        }

        /**
         * an item shown beside the name
         */
        public Line portrait(ItemStack portrait) {
            this.portrait = portrait.copy();
            return this;
        }

        /**
         * the speaker's own model beside the name
         */
        public Line speakerPortrait() {
            this.speakerPortrait = true;
            return this;
        }

        /**
         * a sound played softly every few characters as the line types
         */
        public Line voice(Holder<SoundEvent> voice, float pitch) {
            this.voice = voice;
            this.pitch = pitch;
            return this;
        }

        public Line voice(SoundEvent voice, float pitch) {
            return voice(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(voice), pitch);
        }

        /**
         * replaces the line showing and drops the queue, instead of waiting its turn
         */
        public Line interrupt() {
            this.interrupt = true;
            return this;
        }

        /**
         * a look, or a custom UI registered under that id (UiLooks)
         */
        public Line style(ResourceLocation style) {
            this.style = style;
            return this;
        }

        /**
         * who send() would reach
         */
        public List<ServerPlayer> recipients() {
            if (to != null) return to.stream().filter(p -> !p.hasDisconnected()).toList();
            if (level == null || origin == null) throw new IllegalStateException("A line with no speaker needs at(level, pos) or to(players)");
            UiActions.Context context = new UiActions.Context(level, speaker, null, null, null);
            List<ServerPlayer> out = new ArrayList<>();
            double rangeSq = range * range;
            for (ServerPlayer player : level.players()) {
                if (player.position().distanceToSqr(origin) <= rangeSq && audience.test(player, context)) out.add(player);
            }
            return out;
        }

        /**
         * Sends it, and returns who got it
         */
        public List<ServerPlayer> send() {
            List<ServerPlayer> recipients = recipients();
            if (recipients.isEmpty()) return recipients;
            int hold = ticks >= 0 ? ticks : 40 + text.getString().length();
            SpeechPayload payload = new SpeechPayload(speaker == null ? -1 : speaker.getId(), name, text, portrait, speakerPortrait,
                    hold, bubble && speaker != null, Optional.ofNullable(voice), pitch, interrupt, style);
            for (ServerPlayer player : recipients) PacketDistributor.sendToPlayer(player, payload);
            return recipients;
        }
    }

    /**
     * Server to client: one line. speaker is an entity id, or -1.
     */
    public record SpeechPayload(int speaker, Component name, Component text, ItemStack portrait, boolean speakerPortrait, int ticks,
                                boolean bubble, Optional<Holder<SoundEvent>> voice, float pitch, boolean interrupt,
                                ResourceLocation style) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<SpeechPayload> TYPE = new Type<>(DiceLib.id("speech"));

        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, SpeechPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public SpeechPayload decode(RegistryFriendlyByteBuf buf) {
                return new SpeechPayload(
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.optional(SoundEvent.STREAM_CODEC).decode(buf),
                        ByteBufCodecs.FLOAT.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ResourceLocation.STREAM_CODEC.decode(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, SpeechPayload payload) {
                ByteBufCodecs.VAR_INT.encode(buf, payload.speaker());
                ComponentSerialization.STREAM_CODEC.encode(buf, payload.name());
                ComponentSerialization.STREAM_CODEC.encode(buf, payload.text());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.portrait());
                ByteBufCodecs.BOOL.encode(buf, payload.speakerPortrait());
                ByteBufCodecs.VAR_INT.encode(buf, payload.ticks());
                ByteBufCodecs.BOOL.encode(buf, payload.bubble());
                ByteBufCodecs.optional(SoundEvent.STREAM_CODEC).encode(buf, payload.voice());
                ByteBufCodecs.FLOAT.encode(buf, payload.pitch());
                ByteBufCodecs.BOOL.encode(buf, payload.interrupt());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.style());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private Speech() {
    }
}
