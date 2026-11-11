package org._50dozenhits.dice_library.hologram;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.ui.Audience;
import org._50dozenhits.dice_library.ui.UiActions;
import org._50dozenhits.dice_library.ui.UiLooks;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Holograms: panels that float in the world for chosen players, with buttons pressed by aiming and right-clicking.
 * Who sees one (viewers) and who may press its buttons (interactors) are separate audiences. They are gone when the
 * server stops unless made with saved(), which keeps them with the level (saved ones use UiActions calls, not Java
 * handlers). Server side:
 *
 * <pre>{@code
 * Hologram menu = Holograms.at(level, pos).billboard().width(140).range(24)
 *         .viewers(Audience.tagged("party")).interactors(Audience.players(leader))
 *         .content(p -> p.title(Component.literal("Ashen Crypt")).text("party", Component.literal("Party: 2/4"))
 *                 .button("start", Component.literal("Begin"), (player, hologram) -> startRun(player)))
 *         .spawn();
 * menu.text("party", Component.literal("Party: 3/4"));   menu.remove();
 * Holograms.fromTemplate(level, pos, DiceLib.id("menus/crypt")).orElseThrow().spawn();   // data/<ns>/dicelib/holograms/menus/crypt.json
 * }</pre>
 *
 * <p>It can also go on its own: lifetime(ticks), idleTimeout(ticks) without a press, or tiedTo(player, condition) for
 * as long as that condition holds for that player. onRemove and HologramRemovedEvent say when and why.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class Holograms {
    public static final String FOLDER = "dicelib/holograms";
    /**
     * how often viewers are worked out again, in ticks
     */
    public static final int REFRESH = 10;

    private static final Map<ResourceKey<Level>, Map<UUID, Hologram>> LEVELS = new HashMap<>();
    private static Map<ResourceLocation, Hologram.Spec> templates = Map.of();
    private static Map<ResourceLocation, String> templateProblems = Map.of();

    // ---- making them ----

    public static Builder at(ServerLevel level, Vec3 pos) {
        return new Builder(level, pos);
    }

    /**
     * A builder filled in from a template file, to change further or spawn as it is. Empty for an unknown template
     */
    public static Optional<Builder> fromTemplate(ServerLevel level, Vec3 pos, ResourceLocation template) {
        Hologram.Spec spec = templates.get(template);
        if (spec == null) return Optional.empty();
        Builder builder = new Builder(level, pos).spec(spec);
        builder.template = template;
        return Optional.of(builder);
    }

    public static final class Builder {
        private final ServerLevel level;
        private final Vec3 pos;
        private boolean billboard = true;
        private float yaw;
        private int width = Hologram.DEFAULT_WIDTH;
        private float scale = 1;
        private double range = Hologram.DEFAULT_RANGE;
        private Audience viewers = Audience.everyone();
        private Audience interactors = Audience.everyone();
        private ResourceLocation style = UiLooks.DEFAULT;
        private CompoundTag data = new CompoundTag();
        private HologramPanel panel = HologramPanel.EMPTY;
        private Map<String, HologramPanel.Handler> handlers = Map.of();
        private boolean saved;
        @Nullable
        private Entity follow;
        private Vec3 offset = Vec3.ZERO;
        @Nullable
        private ResourceLocation template;
        private int lifetime;
        private int idleTimeout;
        @Nullable
        private Hologram.Tie tie;
        @Nullable
        private Hologram.RemoveListener onRemove;
        @Nullable
        private Hologram.MessageListener onMessage;

        private Builder(ServerLevel level, Vec3 pos) {
            this.level = level;
            this.pos = pos;
        }

        /**
         * turns to face each viewer, staying upright (the default)
         */
        public Builder billboard() {
            this.billboard = true;
            return this;
        }

        /**
         * stays put, its front facing this way (degrees, 0 = south, 90 = west, like a player's yaw)
         */
        public Builder facing(float yaw) {
            this.billboard = false;
            this.yaw = yaw;
            return this;
        }

        public Builder facing(Direction direction) {
            return facing(direction.toYRot());
        }

        /**
         * stays put, its front turned toward a point once, as it spawns (a player's eyes, a door)
         */
        public Builder facingToward(Vec3 point) {
            return facing(Hologram.yawToward(pos, point, yaw));
        }

        /**
         * goes on its own this many ticks after it spawns (0, the default, = never)
         */
        public Builder lifetime(int ticks) {
            this.lifetime = Math.max(0, ticks);
            return this;
        }

        /**
         * removed once this many ticks pass without a button press (0, the default, = never)
         */
        public Builder idleTimeout(int ticks) {
            this.idleTimeout = Math.max(0, ticks);
            return this;
        }

        /**
         * belongs to this player: it is removed when they leave the server. A saved one waits for them to come back instead.
         */
        public Builder tiedTo(ServerPlayer player) {
            this.tie = new Hologram.Tie(player.getUUID(), Optional.empty());
            return this;
        }

        /**
         * belongs to this player while {@code condition} holds for them: once it fails, the hologram is removed
         * (register one with UiActions.condition), as in tiedTo(player, new UiActions.Ref(MyMod.id("on_quest"), new JsonObject()))
         */
        public Builder tiedTo(ServerPlayer player, UiActions.Ref condition) {
            this.tie = new Hologram.Tie(player.getUUID(), Optional.of(condition));
            return this;
        }

        /**
         * runs once when it is removed, with the reason. Not for saved holograms (HologramRemovedEvent covers those)
         */
        public Builder onRemove(Hologram.RemoveListener listener) {
            this.onRemove = listener;
            return this;
        }

        /**
         * gets what its custom UI sends from a client (HologramScreen.send), after the same checks a click goes
         * through. Not for saved holograms (HologramEvent.Message covers those)
         */
        public Builder onMessage(Hologram.MessageListener listener) {
            this.onMessage = listener;
            return this;
        }

        /**
         * rides along with an entity, offset from its feet. Removed when the entity is
         */
        public Builder follow(Entity entity, Vec3 offset) {
            this.follow = entity;
            this.offset = offset;
            return this;
        }

        /**
         * GUI pixels across. 140 by default
         */
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        /**
         * 1 by default: 64 pixels to a block
         */
        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        /**
         * blocks from it within which viewers see it. 24 by default
         */
        public Builder range(double range) {
            this.range = range;
            return this;
        }

        public Builder viewers(Audience viewers) {
            this.viewers = viewers;
            return this;
        }

        public Builder interactors(Audience interactors) {
            this.interactors = interactors;
            return this;
        }

        /**
         * a look, or a custom UI registered under that id (UiLooks)
         */
        public Builder style(ResourceLocation style) {
            this.style = style;
            return this;
        }

        /**
         * anything a custom UI needs. Sent to clients with the panel and kept by saved holograms
         */
        public Builder data(CompoundTag data) {
            this.data = data.copy();
            return this;
        }

        public Builder content(Consumer<HologramPanel.Builder> content) {
            HologramPanel.Built built = HologramPanel.build(content);
            this.panel = built.panel();
            this.handlers = built.handlers();
            return this;
        }

        public Builder panel(HologramPanel panel) {
            this.panel = panel;
            this.handlers = Map.of();
            return this;
        }

        /**
         * everything a template or a saved hologram holds
         */
        public Builder spec(Hologram.Spec spec) {
            this.panel = spec.panel();
            this.handlers = Map.of();
            this.billboard = spec.billboard();
            this.yaw = spec.yaw();
            this.width = spec.width();
            this.scale = spec.scale();
            this.range = spec.range();
            this.viewers = spec.viewers();
            this.interactors = spec.interactors();
            this.style = spec.style();
            this.data = spec.data().copy();
            this.lifetime = spec.lifetime();
            this.idleTimeout = spec.idleTimeout();
            return this;
        }

        /**
         * whether it turns to face viewers. False for a template that faces one way
         */
        public boolean billboards() {
            return billboard;
        }

        /**
         * kept with the level through restarts. Throws on spawn if anything in it can't be saved
         */
        public Builder saved() {
            this.saved = true;
            return this;
        }

        public Hologram spawn() {
            Hologram.Spec spec = new Hologram.Spec(panel, billboard, yaw, width, scale, range, viewers, interactors, style, lifetime, idleTimeout, data);
            if (saved && onMessage != null) {
                throw new IllegalStateException("A saved hologram can't have an onMessage listener; listen for HologramEvent.Message");
            }
            Hologram hologram = new Hologram(UUID.randomUUID(), level, pos, spec, handlers, saved, follow, offset, template, tie, onRemove);
            hologram.onMessage = onMessage;
            add(hologram);
            return hologram;
        }
    }

    // ---- the store ----

    private static Map<UUID, Hologram> of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level.dimension(), key -> new LinkedHashMap<>());
    }

    private static void add(Hologram hologram) {
        of(hologram.level()).put(hologram.id(), hologram);
        refresh(hologram);
        if (hologram.isSaved()) store(hologram.level()).setDirty();
    }

    public static Optional<Hologram> get(ServerLevel level, UUID id) {
        return Optional.ofNullable(of(level).get(id));
    }

    public static List<Hologram> all(ServerLevel level) {
        return List.copyOf(of(level).values());
    }

    public static Optional<Hologram> nearest(ServerLevel level, Vec3 pos, double within) {
        return of(level).values().stream().filter(h -> h.pos().distanceToSqr(pos) <= within * within)
                .min(Comparator.comparingDouble(h -> h.pos().distanceToSqr(pos)));
    }

    // Takes it away from the store and from every viewer, then runs its listener and posts HologramRemovedEvent
    static void remove(Hologram hologram, Hologram.RemoveReason reason) {
        if (hologram.isRemoved()) return;
        of(hologram.level()).remove(hologram.id());
        hologram.markRemoved();
        for (UUID viewer : hologram.viewers()) {
            ServerPlayer player = hologram.level().getServer().getPlayerList().getPlayer(viewer);
            if (player != null) PacketDistributor.sendToPlayer(player, new HologramRemovePayload(hologram.id()));
        }
        hologram.viewing.clear();
        if (hologram.isSaved()) store(hologram.level()).setDirty();
        hologram.runRemoveListener(reason);
        NeoForge.EVENT_BUS.post(new HologramRemovedEvent(hologram, reason));
    }

    // a timer changed: nothing to send, but a saved hologram must keep the new clocks
    static void timersChanged(Hologram hologram) {
        if (hologram.isSaved()) store(hologram.level()).setDirty();
    }

    // An online player by id. Falls back to the levels' own lists, which also hold players that aren't in the player
    // list (fake players).
    @Nullable
    static ServerPlayer findPlayer(MinecraftServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) return player;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getPlayerByUUID(id) instanceof ServerPlayer found && !found.isRemoved()) return found;
        }
        return null;
    }

    // content or place changed: everyone viewing gets it now, and the viewers are worked out again
    static void changed(Hologram hologram) {
        for (UUID viewer : hologram.viewers()) {
            ServerPlayer player = hologram.level().getServer().getPlayerList().getPlayer(viewer);
            if (player == null) continue;
            List<String> disabled = hologram.disabledFor(player);
            hologram.viewing.put(viewer, disabled);
            PacketDistributor.sendToPlayer(player, payload(hologram, viewer, disabled));
        }
        refresh(hologram);
        if (hologram.isSaved()) store(hologram.level()).setDirty();
    }

    // Works out who sees it: new viewers get it, old ones lose it, and anyone whose greyed-out buttons changed gets it again
    static void refresh(Hologram hologram) {
        if (hologram.isRemoved()) return;
        Map<UUID, ServerPlayer> now = new HashMap<>();
        for (ServerPlayer player : hologram.level().players()) if (hologram.canSee(player)) now.put(player.getUUID(), player);
        for (UUID old : List.copyOf(hologram.viewing.keySet())) {
            if (now.containsKey(old)) continue;
            hologram.viewing.remove(old);
            ServerPlayer player = hologram.level().getServer().getPlayerList().getPlayer(old);
            if (player != null) PacketDistributor.sendToPlayer(player, new HologramRemovePayload(hologram.id()));
        }
        for (ServerPlayer player : now.values()) {
            List<String> disabled = hologram.disabledFor(player);
            List<String> sent = hologram.viewing.get(player.getUUID());
            if (disabled.equals(sent)) continue;
            hologram.viewing.put(player.getUUID(), disabled);
            PacketDistributor.sendToPlayer(player, payload(hologram, player.getUUID(), disabled));
        }
    }

    // one viewer's data changed: they get the hologram again, the others nothing
    static void resend(Hologram hologram, ServerPlayer player) {
        List<String> disabled = hologram.viewing.get(player.getUUID());
        if (disabled != null) PacketDistributor.sendToPlayer(player, payload(hologram, player.getUUID(), disabled));
    }

    private static HologramPayload payload(Hologram hologram, UUID viewer, List<String> disabled) {
        Hologram.Spec spec = hologram.spec();
        Entity follow = hologram.following();
        return new HologramPayload(hologram.id(), hologram.version(), hologram.pos(), follow == null ? -1 : follow.getId(), hologram.offset(),
                spec.billboard(), spec.yaw(), spec.width(), spec.scale(), spec.style(), hologram.dataFor(viewer), spec.panel(), disabled);
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % REFRESH != 0) return;
        Map<UUID, Hologram> holograms = LEVELS.get(level.dimension());
        if (holograms == null || holograms.isEmpty()) return;
        for (Hologram hologram : List.copyOf(holograms.values())) {
            Hologram.RemoveReason reason = hologram.dueToGo();
            if (reason != null) {
                remove(hologram, reason);
                continue;
            }
            refresh(hologram);
        }
    }

    // A player who changes dimension or logs out is dropped from viewing. They are added back when they are in range.
    // Holograms tied to a player who logs out are removed with them, unless they are saved.
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID player = event.getEntity().getUUID();
        forget(player);
        for (Map<UUID, Hologram> holograms : LEVELS.values()) {
            for (Hologram hologram : holograms.values()) hologram.forget(player);
        }
        // a copy, since an onRemove listener may spawn a hologram in a level not in the map yet
        for (Map<UUID, Hologram> holograms : List.copyOf(LEVELS.values())) {
            for (Hologram hologram : List.copyOf(holograms.values())) {
                if (!hologram.isSaved() && hologram.tie().map(tie -> tie.player().equals(player)).orElse(false)) {
                    remove(hologram, Hologram.RemoveReason.OWNER_GONE);
                }
            }
        }
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        forget(event.getEntity().getUUID());
    }

    private static void forget(UUID player) {
        for (Map<UUID, Hologram> holograms : LEVELS.values()) {
            for (Hologram hologram : holograms.values()) hologram.viewing.remove(player);
        }
    }

    @SubscribeEvent
    static void onStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    // ---- clicks ----

    /**
     * A click from a player's crosshair. It counts only when the hologram is there and unchanged since the player saw
     * it, they are viewing it, may press its buttons, are within REACH, and the button is one they can press.
     */
    public static boolean click(ServerPlayer player, UUID id, int version, String button) {
        Hologram hologram = of(player.serverLevel()).get(id);
        if (hologram == null || hologram.version() != version || !hologram.viewing.containsKey(player.getUUID())) return false;
        double reach = Hologram.REACH + 2;
        if (player.getEyePosition().distanceToSqr(hologram.pos()) > reach * reach || !hologram.canUse(player)) return false;
        HologramPanel.Button pressed = hologram.panel().button(button);
        if (pressed == null || !UiActions.testAll(pressed.conditions(), player, hologram.context())) return false;
        if (NeoForge.EVENT_BUS.post(new HologramEvent.Click(player, hologram, button)).isCanceled()) return false;
        hologram.used();
        HologramPanel.Handler handler = hologram.handler(button);
        if (handler != null) handler.click(player, hologram);
        else pressed.action().ifPresent(action -> UiActions.run(action, player, hologram.context()));
        return true;
    }

    /**
     * A message from a player's custom hologram UI. It counts only when the hologram is there, they are viewing it,
     * may interact with it and are within REACH. Then HologramEvent.Message is posted and the onMessage listener runs.
     */
    public static boolean message(ServerPlayer player, UUID id, String action, CompoundTag data) {
        Hologram hologram = of(player.serverLevel()).get(id);
        if (hologram == null || !hologram.viewing.containsKey(player.getUUID())) return false;
        double reach = Hologram.REACH + 2;
        if (player.getEyePosition().distanceToSqr(hologram.pos()) > reach * reach || !hologram.canUse(player)) return false;
        if (NeoForge.EVENT_BUS.post(new HologramEvent.Message(player, hologram, action, data)).isCanceled()) return false;
        hologram.used();
        if (hologram.onMessage != null) {
            try {
                hologram.onMessage.message(player, hologram, action, data);
            } catch (RuntimeException e) {
                DiceLib.LOGGER.error("Hologram {}'s onMessage threw on \"{}\"", id, action, e);
            }
        }
        return true;
    }

    // ---- templates ----

    public static Optional<Hologram.Spec> template(ResourceLocation id) {
        return Optional.ofNullable(templates.get(id));
    }

    public static List<ResourceLocation> templateIds() {
        List<ResourceLocation> ids = new ArrayList<>(templates.keySet());
        ids.sort(null);
        return ids;
    }

    /**
     * what was wrong with a template file. Empty when it loaded
     */
    public static Optional<String> templateProblem(ResourceLocation id) {
        return Optional.ofNullable(templateProblems.get(id));
    }

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader(event.getRegistryAccess()));
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        private final HolderLookup.Provider registries;

        Loader(HolderLookup.Provider registries) {
            super(new Gson(), FOLDER);
            this.registries = registries;
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            Map<ResourceLocation, Hologram.Spec> loaded = new HashMap<>();
            Map<ResourceLocation, String> problems = new HashMap<>();
            files.forEach((id, json) -> {
                try {
                    Hologram.Spec.CODEC.parse(ops, json).resultOrPartial(error -> problems.put(id, error)).ifPresent(spec -> loaded.put(id, spec));
                } catch (RuntimeException e) {
                    problems.put(id, e.getMessage());
                }
            });
            problems.forEach((id, problem) -> DiceLib.LOGGER.warn("Hologram template {}: {}", id, problem));
            templates = Map.copyOf(loaded);
            templateProblems = Map.copyOf(problems);
        }
    }

    // ---- saving ----

    // expiresAt and lastUsed are level game times, which stand still while the server is down
    private record Saved(UUID id, Vec3 pos, Hologram.Spec spec, Optional<ResourceLocation> template, Optional<Long> expiresAt,
                         long lastUsed, Optional<Hologram.Tie> tie) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Saved::id),
                Vec3.CODEC.fieldOf("pos").forGetter(Saved::pos),
                Hologram.Spec.CODEC.fieldOf("spec").forGetter(Saved::spec),
                ResourceLocation.CODEC.optionalFieldOf("template").forGetter(Saved::template),
                Codec.LONG.optionalFieldOf("expires_at").forGetter(Saved::expiresAt),
                Codec.LONG.optionalFieldOf("last_used", 0L).forGetter(Saved::lastUsed),
                Hologram.Tie.CODEC.optionalFieldOf("tie").forGetter(Saved::tie)
        ).apply(i, Saved::new));

        static Saved of(Hologram hologram, Hologram.Spec spec) {
            Optional<Long> expires = hologram.expiresAt() == Long.MAX_VALUE ? Optional.empty() : Optional.of(hologram.expiresAt());
            return new Saved(hologram.id(), hologram.pos(), spec, Optional.ofNullable(hologram.template()), expires, hologram.lastUsed(), hologram.tie());
        }

        Hologram load(ServerLevel level) {
            Hologram hologram = new Hologram(id, level, pos, spec, Map.of(), true, null, Vec3.ZERO, template.orElse(null), tie.orElse(null), null);
            hologram.restoreClocks(expiresAt.orElse(Long.MAX_VALUE), lastUsed);
            return hologram;
        }
    }

    // The saved holograms of one level, written from the live store when the level saves
    private static final class Store extends SavedData {
        // the level's live holograms. Kept here too, so a save after the level has unloaded still writes them
        private final Map<UUID, Hologram> holograms;

        private Store(ServerLevel level) {
            this.holograms = of(level);
        }

        private static Store load(ServerLevel level, CompoundTag tag, HolderLookup.Provider registries) {
            Store store = new Store(level);
            RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
            for (Tag entry : tag.getList("holograms", Tag.TAG_COMPOUND)) {
                Saved.CODEC.parse(ops, entry).resultOrPartial(error -> DiceLib.LOGGER.warn("Dropped a saved hologram: {}", error)).ifPresent(saved -> {
                    Hologram hologram = saved.load(level);
                    of(level).put(hologram.id(), hologram);
                });
            }
            return store;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
            ListTag list = new ListTag();
            for (Hologram hologram : holograms.values()) {
                hologram.saveableSpec().ifPresent(spec -> Saved.CODEC.encodeStart(ops, Saved.of(hologram, spec))
                        .resultOrPartial(error -> DiceLib.LOGGER.warn("Couldn't save hologram {}: {}", hologram.id(), error)).ifPresent(list::add));
            }
            tag.put("holograms", list);
            return tag;
        }
    }

    private static Store store(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> new Store(level),
                (tag, registries) -> Store.load(level, tag, registries)), "dicelib_holograms");
    }

    @SubscribeEvent
    static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) store(level);
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level.dimension());
    }

    // ---- payloads ----

    private static final StreamCodec<io.netty.buffer.ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /**
     * Server to client: a hologram to show, or new content for one already showing. follow is an entity id, or -1.
     */
    public record HologramPayload(UUID id, int version, Vec3 pos, int follow, Vec3 offset, boolean billboard, float yaw, int width,
                                  float scale, ResourceLocation style, CompoundTag data, HologramPanel panel, List<String> disabled) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<HologramPayload> TYPE = new Type<>(DiceLib.id("hologram"));

        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, HologramPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public HologramPayload decode(RegistryFriendlyByteBuf buf) {
                return new HologramPayload(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), VEC3.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), VEC3.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.FLOAT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.FLOAT.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.COMPOUND_TAG.decode(buf),
                        HologramPanel.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, HologramPayload payload) {
                UUIDUtil.STREAM_CODEC.encode(buf, payload.id());
                ByteBufCodecs.VAR_INT.encode(buf, payload.version());
                VEC3.encode(buf, payload.pos());
                ByteBufCodecs.VAR_INT.encode(buf, payload.follow());
                VEC3.encode(buf, payload.offset());
                ByteBufCodecs.BOOL.encode(buf, payload.billboard());
                ByteBufCodecs.FLOAT.encode(buf, payload.yaw());
                ByteBufCodecs.VAR_INT.encode(buf, payload.width());
                ByteBufCodecs.FLOAT.encode(buf, payload.scale());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.style());
                ByteBufCodecs.COMPOUND_TAG.encode(buf, payload.data());
                HologramPanel.STREAM_CODEC.encode(buf, payload.panel());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, payload.disabled());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: stop showing it
     */
    public record HologramRemovePayload(UUID id) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<HologramRemovePayload> TYPE = new Type<>(DiceLib.id("hologram_remove"));
        @ApiStatus.Internal
        public static final StreamCodec<io.netty.buffer.ByteBuf, HologramRemovePayload> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, HologramRemovePayload::id, HologramRemovePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: a button pressed on the version the client was showing
     */
    public record HologramClickPayload(UUID id, int version, String button) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<HologramClickPayload> TYPE = new Type<>(DiceLib.id("hologram_click"));
        @ApiStatus.Internal
        public static final StreamCodec<io.netty.buffer.ByteBuf, HologramClickPayload> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, HologramClickPayload::id,
                ByteBufCodecs.VAR_INT, HologramClickPayload::version,
                ByteBufCodecs.stringUtf8(64), HologramClickPayload::button,
                HologramClickPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @ApiStatus.Internal
        public static void handle(HologramClickPayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) click(player, payload.id(), payload.version(), payload.button());
        }
    }

    /**
     * Client to server: a message from a custom hologram UI. The tag is capped at 32 KiB.
     */
    public record HologramMessagePayload(UUID id, String action, CompoundTag data) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<HologramMessagePayload> TYPE = new Type<>(DiceLib.id("hologram_message"));
        @ApiStatus.Internal
        public static final StreamCodec<io.netty.buffer.ByteBuf, HologramMessagePayload> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, HologramMessagePayload::id,
                ByteBufCodecs.stringUtf8(64), HologramMessagePayload::action,
                ByteBufCodecs.compoundTagCodec(() -> NbtAccounter.create(32768)), HologramMessagePayload::data,
                HologramMessagePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @ApiStatus.Internal
        public static void handle(HologramMessagePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) message(player, payload.id(), payload.action(), payload.data());
        }
    }

    // ---- events ----

    /**
     * Server side, on NeoForge.EVENT_BUS.
     */
    public abstract static class HologramEvent extends PlayerEvent {
        private final Hologram hologram;

        protected HologramEvent(ServerPlayer player, Hologram hologram) {
            super(player);
            this.hologram = hologram;
        }

        public ServerPlayer getServerPlayer() {
            return (ServerPlayer) getEntity();
        }

        public Hologram getHologram() {
            return hologram;
        }

        /**
         * A button was pressed and passed every check, before it runs. Cancel to ignore it
         */
        public static class Click extends HologramEvent implements ICancellableEvent {
            private final String button;

            public Click(ServerPlayer player, Hologram hologram, String button) {
                super(player, hologram);
                this.button = button;
            }

            public String getButton() {
                return button;
            }
        }

        /**
         * A custom hologram UI sent a message and it passed every check, before the onMessage listener runs. Cancel to
         * ignore it. This is the only way to hear from a saved hologram's custom UI.
         */
        public static class Message extends HologramEvent implements ICancellableEvent {
            private final String action;
            private final CompoundTag data;

            public Message(ServerPlayer player, Hologram hologram, String action, CompoundTag data) {
                super(player, hologram);
                this.action = action;
                this.data = data;
            }

            public String getAction() {
                return action;
            }

            public CompoundTag getData() {
                return data;
            }
        }
    }

    /**
     * A hologram went, for any reason (removed in code or by command, a timer, its entity, its player or its condition),
     * after its viewers lost it and its onRemove listener ran. Not posted when the server stops or its level unloads.
     */
    public static class HologramRemovedEvent extends Event {
        private final Hologram hologram;
        private final Hologram.RemoveReason reason;

        public HologramRemovedEvent(Hologram hologram, Hologram.RemoveReason reason) {
            this.hologram = hologram;
            this.reason = reason;
        }

        public Hologram getHologram() {
            return hologram;
        }

        public Hologram.RemoveReason getReason() {
            return reason;
        }
    }

    private Holograms() {
    }
}
