package org._50dozenhits.dice_library.hologram;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.ui.Audience;
import org._50dozenhits.dice_library.ui.UiActions;
import org._50dozenhits.dice_library.ui.UiLooks;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Mob panels: a small panel on the HUD tied to a mob, either beside it in brackets or pinned to a screen corner with a
 * line down to it. The server says what it shows. Each client reads the mob's health itself, so health costs nothing.
 * One panel per mob. Server side:
 *
 * <pre>{@code
 * MobPanel panel = MobPanels.on(boss).to(Audience.everyone()).range(48).pinned(MobPanels.Corner.TOP_LEFT)
 *         .title(boss.getDisplayName()).icon(new ItemStack(Items.WITHER_SKELETON_SKULL)).health()
 *         .line("phase", Component.literal("Phase 1")).bar("rage", Component.literal("Rage"), 0, 100, 0xFFC03030)
 *         .show();
 * panel.set("phase", Component.literal("Phase 2"));   panel.bar("rage", 60);   panel.hide();
 * }</pre>
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class MobPanels {
    public static final int DEFAULT_WIDTH = 110;
    public static final double DEFAULT_RANGE = 32;
    public static final int DEFAULT_COLOR = 0xFF6EC6FF;

    public enum Mode {
        /**
         * next to the mob, its outline marked with corner brackets
         */
        BESIDE,
        /**
         * in a corner of the screen, a line running down to the mob
         */
        PINNED
    }

    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        MIDDLE_LEFT,
        MIDDLE_RIGHT
    }

    private static final Map<UUID, MobPanel> PANELS = new LinkedHashMap<>();
    private static final Set<MobPanel> CHANGED = new HashSet<>();

    public static Builder on(LivingEntity mob) {
        return new Builder(mob);
    }

    /**
     * the panel on the mob, or null when it has none
     */
    @Nullable
    public static MobPanel get(LivingEntity mob) {
        return PANELS.get(mob.getUUID());
    }

    public static List<MobPanel> all() {
        return List.copyOf(PANELS.values());
    }

    public static final class Builder {
        private final LivingEntity mob;
        private Audience audience = Audience.everyone();
        private double range = DEFAULT_RANGE;
        private Mode mode = Mode.BESIDE;
        private Corner corner = Corner.TOP_LEFT;
        private Component title;
        private ItemStack icon = ItemStack.EMPTY;
        private boolean mobIcon;
        private final HologramPanel.Builder body = new HologramPanel.Builder();
        private ResourceLocation style = UiLooks.DEFAULT;
        private boolean throughWalls = true;
        private int width = DEFAULT_WIDTH;
        private int color = DEFAULT_COLOR;

        private Builder(LivingEntity mob) {
            this.mob = mob;
            this.title = mob.getDisplayName();
        }

        public Builder to(Audience audience) {
            this.audience = audience;
            return this;
        }

        /**
         * blocks from the mob within which players see it. 32 by default
         */
        public Builder range(double range) {
            this.range = range;
            return this;
        }

        public Builder beside() {
            this.mode = Mode.BESIDE;
            return this;
        }

        public Builder pinned(Corner corner) {
            this.mode = Mode.PINNED;
            this.corner = corner;
            return this;
        }

        /**
         * the mob's own name by default
         */
        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        public Builder icon(ItemStack icon) {
            this.icon = icon.copy();
            this.mobIcon = false;
            return this;
        }

        /**
         * the mob's own head as the icon
         */
        public Builder mobIcon() {
            this.mobIcon = true;
            this.icon = ItemStack.EMPTY;
            return this;
        }

        public Builder health() {
            body.health();
            return this;
        }

        /**
         * a line of text that set(key, text) can change
         */
        public Builder line(String key, Component text) {
            body.text(key, text);
            return this;
        }

        public Builder bar(String key, @Nullable Component text, float value, float max, int color) {
            body.bar(key, text, value, max, color);
            return this;
        }

        /**
         * any other parts (items, dividers, rows) under the title
         */
        public Builder parts(Consumer<HologramPanel.Builder> parts) {
            parts.accept(body);
            return this;
        }

        public Builder style(ResourceLocation style) {
            this.style = style;
            return this;
        }

        /**
         * false: hidden while the mob is out of the viewer's sight
         */
        public Builder throughWalls(boolean throughWalls) {
            this.throughWalls = throughWalls;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        /**
         * the brackets and the line
         */
        public Builder color(int color) {
            this.color = color;
            return this;
        }

        /**
         * Shows it, replacing any panel the mob already has
         */
        public MobPanel show() {
            MobPanel old = PANELS.remove(mob.getUUID());
            if (old != null) old.drop(true);
            MobPanel panel = new MobPanel(mob, audience, range, mode, corner, title, icon, mobIcon, body.toPanel(), style, throughWalls,
                    width, color);
            PANELS.put(mob.getUUID(), panel);
            panel.refresh();
            return panel;
        }
    }

    public static final class MobPanel {
        private final LivingEntity mob;
        private Audience audience;
        private final double range;
        private Mode mode;
        private Corner corner;
        private Component title;
        private ItemStack icon;
        private boolean mobIcon;
        private HologramPanel body;
        private final ResourceLocation style;
        private final boolean throughWalls;
        private final int width;
        private final int color;
        private final Set<UUID> viewing = new HashSet<>();
        private boolean hidden;

        private MobPanel(LivingEntity mob, Audience audience, double range, Mode mode, Corner corner, Component title, ItemStack icon,
                         boolean mobIcon, HologramPanel body, ResourceLocation style, boolean throughWalls, int width, int color) {
            this.mob = mob;
            this.audience = audience;
            this.range = range;
            this.mode = mode;
            this.corner = corner;
            this.title = title;
            this.icon = icon;
            this.mobIcon = mobIcon;
            this.body = body;
            this.style = style;
            this.throughWalls = throughWalls;
            this.width = width;
            this.color = color;
        }

        public LivingEntity mob() {
            return mob;
        }

        public HologramPanel body() {
            return body;
        }

        public Component title() {
            return title;
        }

        public boolean isShown() {
            return !hidden;
        }

        public Set<UUID> viewers() {
            return Set.copyOf(viewing);
        }

        public MobPanel set(String key, Component text) {
            return part(key, new HologramPanel.Text(key, text));
        }

        /**
         * a bar's value, its other settings kept
         */
        public MobPanel bar(String key, float value) {
            if (!(body.find(key) instanceof HologramPanel.Bar bar) || bar.value() == value) return this;
            return part(key, new HologramPanel.Bar(key, bar.text(), value, bar.max(), bar.color()));
        }

        public MobPanel part(String key, HologramPanel.Part part) {
            body = body.with(key, part);
            CHANGED.add(this);
            return this;
        }

        public MobPanel title(Component title) {
            this.title = title;
            CHANGED.add(this);
            return this;
        }

        public MobPanel icon(ItemStack icon) {
            this.icon = icon.copy();
            this.mobIcon = false;
            CHANGED.add(this);
            return this;
        }

        public MobPanel pinned(Corner corner) {
            this.mode = Mode.PINNED;
            this.corner = corner;
            CHANGED.add(this);
            return this;
        }

        public MobPanel beside() {
            this.mode = Mode.BESIDE;
            CHANGED.add(this);
            return this;
        }

        public MobPanel to(Audience audience) {
            this.audience = audience;
            refresh();
            return this;
        }

        public void hide() {
            if (PANELS.get(mob.getUUID()) == this) PANELS.remove(mob.getUUID());
            drop(true);
        }

        private void drop(boolean tell) {
            hidden = true;
            CHANGED.remove(this);
            if (tell && mob.level() instanceof ServerLevel level) {
                for (UUID viewer : viewing) {
                    ServerPlayer player = level.getServer().getPlayerList().getPlayer(viewer);
                    if (player != null) PacketDistributor.sendToPlayer(player, new MobPanelRemovePayload(mob.getId()));
                }
            }
            viewing.clear();
        }

        private boolean canSee(ServerPlayer player) {
            return player.level() == mob.level() && player.distanceToSqr(mob) <= range * range
                    && audience.test(player, new UiActions.Context(player.serverLevel(), mob, null, null, null));
        }

        private MobPanelPayload payload() {
            return new MobPanelPayload(mob.getId(), mode, corner, title, icon, mobIcon, body, style, throughWalls, width, color);
        }

        void refresh() {
            if (hidden || !(mob.level() instanceof ServerLevel level)) return;
            MobPanelPayload payload = null;
            for (UUID old : List.copyOf(viewing)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(old);
                if (player != null && canSee(player)) continue;
                viewing.remove(old);
                if (player != null) PacketDistributor.sendToPlayer(player, new MobPanelRemovePayload(mob.getId()));
            }
            for (ServerPlayer player : level.players()) {
                if (viewing.contains(player.getUUID()) || !canSee(player)) continue;
                viewing.add(player.getUUID());
                if (payload == null) payload = payload();
                PacketDistributor.sendToPlayer(player, payload);
            }
        }

        void flush() {
            if (hidden || !(mob.level() instanceof ServerLevel level)) return;
            MobPanelPayload payload = payload();
            for (UUID viewer : viewing) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(viewer);
                if (player != null) PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    // changes made during a tick go out once, at its end. Viewers are worked out every 10 ticks
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (!CHANGED.isEmpty()) {
            for (MobPanel panel : List.copyOf(CHANGED)) panel.flush();
            CHANGED.clear();
        }
        if (event.getServer().getTickCount() % Holograms.REFRESH != 0 || PANELS.isEmpty()) return;
        for (MobPanel panel : List.copyOf(PANELS.values())) {
            if (panel.mob.isRemoved() || !panel.mob.isAlive()) panel.hide();
            else panel.refresh();
        }
    }

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        MobPanel panel = PANELS.get(event.getEntity().getUUID());
        if (panel != null && !event.getEntity().level().isClientSide()) panel.hide();
    }

    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        MobPanel panel = PANELS.get(event.getEntity().getUUID());
        if (panel != null && panel.mob == event.getEntity()) panel.hide();
        for (MobPanel other : PANELS.values()) other.viewing.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        for (MobPanel panel : PANELS.values()) panel.viewing.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onStopped(ServerStoppedEvent event) {
        PANELS.clear();
        CHANGED.clear();
    }

    /**
     * Server to client: a panel to show or its new content. entity is the mob's id.
     */
    public record MobPanelPayload(int entity, Mode mode, Corner corner, Component title, ItemStack icon, boolean mobIcon, HologramPanel body,
                                  ResourceLocation style, boolean throughWalls, int width, int color) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<MobPanelPayload> TYPE = new Type<>(DiceLib.id("mob_panel"));

        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, MobPanelPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public MobPanelPayload decode(RegistryFriendlyByteBuf buf) {
                return new MobPanelPayload(ByteBufCodecs.VAR_INT.decode(buf), Mode.values()[ByteBufCodecs.VAR_INT.decode(buf)],
                        Corner.values()[ByteBufCodecs.VAR_INT.decode(buf)], ComponentSerialization.STREAM_CODEC.decode(buf),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), ByteBufCodecs.BOOL.decode(buf), HologramPanel.STREAM_CODEC.decode(buf),
                        ResourceLocation.STREAM_CODEC.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.INT.decode(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, MobPanelPayload payload) {
                ByteBufCodecs.VAR_INT.encode(buf, payload.entity());
                ByteBufCodecs.VAR_INT.encode(buf, payload.mode().ordinal());
                ByteBufCodecs.VAR_INT.encode(buf, payload.corner().ordinal());
                ComponentSerialization.STREAM_CODEC.encode(buf, payload.title());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.icon());
                ByteBufCodecs.BOOL.encode(buf, payload.mobIcon());
                HologramPanel.STREAM_CODEC.encode(buf, payload.body());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.style());
                ByteBufCodecs.BOOL.encode(buf, payload.throughWalls());
                ByteBufCodecs.VAR_INT.encode(buf, payload.width());
                ByteBufCodecs.INT.encode(buf, payload.color());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the mob's panel is gone
     */
    public record MobPanelRemovePayload(int entity) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<MobPanelRemovePayload> TYPE = new Type<>(DiceLib.id("mob_panel_remove"));
        @ApiStatus.Internal
        public static final StreamCodec<io.netty.buffer.ByteBuf, MobPanelRemovePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MobPanelRemovePayload::entity, MobPanelRemovePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private MobPanels() {
    }
}
