package org._50dozenhits.dice_library.hologram;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.ui.Audience;
import org._50dozenhits.dice_library.ui.UiActions;
import org._50dozenhits.dice_library.ui.UiLooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A panel floating in the world, made with Holograms.at(...).spawn() or placed from a template. Only its viewers see
 * it, and only its interactors can press its buttons. Everyone viewing sees the same content, and a change reaches
 * all of them together. Server side. Change it with update, set, move, facing, viewers and interactors. Take it away
 * with remove, or let a timer or a tie to a player take it away (RemoveReason says which).
 */
public final class Hologram {
    public static final int DEFAULT_WIDTH = 140;
    public static final double DEFAULT_RANGE = 24;
    /**
     * blocks a player may be from it to press a button
     */
    public static final double REACH = 8;
    private static final long NEVER = Long.MAX_VALUE;

    /**
     * How it looks and who it is for. What a template file holds and what a saved hologram keeps.
     * <pre>{@code
     * width  GUI pixels across (140)     scale  blocks per pixel times 64, so 1 is 1/64 of a block a pixel
     * billboard  turns to face each viewer. Otherwise it faces yaw (degrees, 0 = south)
     * lifetime  ticks until it goes on its own (0 = never)    idleTimeout  ticks without a press until it goes (0 = never)
     * style  a look or a custom UI (UiLooks)    data  anything a custom UI needs, sent to clients with the panel
     * }</pre>
     */
    public record Spec(HologramPanel panel, boolean billboard, float yaw, int width, float scale, double range, Audience viewers,
                       Audience interactors, ResourceLocation style, int lifetime, int idleTimeout, CompoundTag data) {
        public static final Codec<Spec> CODEC = RecordCodecBuilder.create(i -> i.group(
                HologramPanel.CODEC.fieldOf("parts").forGetter(Spec::panel),
                Codec.BOOL.optionalFieldOf("billboard", true).forGetter(Spec::billboard),
                Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(Spec::yaw),
                Codec.INT.optionalFieldOf("width", DEFAULT_WIDTH).forGetter(Spec::width),
                Codec.FLOAT.optionalFieldOf("scale", 1f).forGetter(Spec::scale),
                Codec.DOUBLE.optionalFieldOf("range", DEFAULT_RANGE).forGetter(Spec::range),
                Audience.CODEC.optionalFieldOf("viewers", Audience.everyone()).forGetter(Spec::viewers),
                Audience.CODEC.optionalFieldOf("interactors", Audience.everyone()).forGetter(Spec::interactors),
                ResourceLocation.CODEC.optionalFieldOf("style", UiLooks.DEFAULT).forGetter(Spec::style),
                ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("lifetime", 0).forGetter(Spec::lifetime),
                ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("idle_timeout", 0).forGetter(Spec::idleTimeout),
                CompoundTag.CODEC.optionalFieldOf("data", new CompoundTag()).forGetter(Spec::data)
        ).apply(i, Spec::new));

        public Spec withPanel(HologramPanel panel) {
            return new Spec(panel, billboard, yaw, width, scale, range, viewers, interactors, style, lifetime, idleTimeout, data);
        }

        public Spec withFacing(boolean billboard, float yaw) {
            return new Spec(panel, billboard, yaw, width, scale, range, viewers, interactors, style, lifetime, idleTimeout, data);
        }

        public Spec withAudiences(Audience viewers, Audience interactors) {
            return new Spec(panel, billboard, yaw, width, scale, range, viewers, interactors, style, lifetime, idleTimeout, data);
        }

        public Spec withData(CompoundTag data) {
            return new Spec(panel, billboard, yaw, width, scale, range, viewers, interactors, style, lifetime, idleTimeout, data);
        }

        public Spec withTimers(int lifetime, int idleTimeout) {
            return new Spec(panel, billboard, yaw, width, scale, range, viewers, interactors, style, Math.max(0, lifetime), Math.max(0, idleTimeout), data);
        }
    }

    /**
     * A hologram that belongs to a player: it is removed when they leave the server (a saved one waits for them instead),
     * and, with a condition, as soon as that condition fails for them (checked twice a second)
     */
    public record Tie(UUID player, Optional<UiActions.Ref> condition) {
        public static final Codec<Tie> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Tie::player),
                UiActions.Ref.CODEC.optionalFieldOf("condition").forGetter(Tie::condition)
        ).apply(i, Tie::new));
    }

    /**
     * Why a hologram went
     */
    public enum RemoveReason {
        /**
         * remove() was called
         */
        REMOVED,
        /**
         * its lifetime ran out
         */
        EXPIRED,
        /**
         * nobody pressed a button within its idle timeout
         */
        IDLE,
        /**
         * the entity it followed died or left
         */
        ENTITY_GONE,
        /**
         * the player it is tied to left the server
         */
        OWNER_GONE,
        /**
         * the condition it is tied to failed for its player
         */
        CONDITION_FAILED
    }

    /**
     * Runs once when the hologram is removed, after viewers have lost it
     */
    @FunctionalInterface
    public interface RemoveListener {
        void removed(Hologram hologram, RemoveReason reason);
    }

    /**
     * Gets what a custom hologram UI sent (HologramScreen.send), for a player who is viewing the hologram, may
     * interact with it and stands in reach. The action and the data are whatever the client chose to send: check them.
     */
    @FunctionalInterface
    public interface MessageListener {
        void message(ServerPlayer player, Hologram hologram, String action, CompoundTag data);
    }

    private final UUID id;
    private final ServerLevel level;
    private Vec3 pos;
    private Spec spec;
    private Map<String, HologramPanel.Handler> handlers;
    private final boolean saved;
    @Nullable
    private final Entity follow;
    private final Vec3 offset;
    @Nullable
    private final ResourceLocation template;
    @Nullable
    private final Tie tie;
    @Nullable
    private final RemoveListener onRemove;
    @Nullable
    MessageListener onMessage;
    // data one viewer alone is sent, laid over the hologram's own
    private final Map<UUID, CompoundTag> personal = new HashMap<>();
    private int version;
    private boolean removed;
    // game times: when its lifetime ends (NEVER for none), and when a button was last pressed (or it was made)
    private long expiresAt;
    private long lastUsed;
    // who sees it now, and the buttons each of them was sent as greyed out
    final Map<UUID, List<String>> viewing = new HashMap<>();

    Hologram(UUID id, ServerLevel level, Vec3 pos, Spec spec, Map<String, HologramPanel.Handler> handlers, boolean saved,
             @Nullable Entity follow, Vec3 offset, @Nullable ResourceLocation template, @Nullable Tie tie, @Nullable RemoveListener onRemove) {
        this.id = id;
        this.level = level;
        this.pos = pos;
        this.spec = spec;
        this.handlers = Map.copyOf(handlers);
        this.saved = saved;
        this.follow = follow;
        this.offset = offset;
        this.template = template;
        this.tie = tie;
        this.onRemove = onRemove;
        long now = level.getGameTime();
        this.expiresAt = spec.lifetime() > 0 ? now + spec.lifetime() : NEVER;
        this.lastUsed = now;
        if (saved) checkSaveable(spec, this.handlers, follow, onRemove);
    }

    static void checkSaveable(Spec spec, Map<String, HologramPanel.Handler> handlers, @Nullable Entity follow, @Nullable RemoveListener onRemove) {
        if (!handlers.isEmpty()) throw new IllegalStateException("A saved hologram's buttons need UiActions calls, not Java handlers");
        if (!spec.viewers().saveable() || !spec.interactors().saveable()) throw new IllegalStateException("A saved hologram can't use Audience.of(predicate)");
        if (follow != null) throw new IllegalStateException("A saved hologram can't follow an entity");
        if (onRemove != null) throw new IllegalStateException("A saved hologram can't have an onRemove listener; listen for HologramRemovedEvent");
        for (HologramPanel.Button button : spec.panel().buttons()) {
            if (button.action().isEmpty()) throw new IllegalStateException("Button " + button.id() + " of a saved hologram has no action");
        }
    }

    public UUID id() {
        return id;
    }

    public ServerLevel level() {
        return level;
    }

    /**
     * where it is now: the followed entity's position plus the offset, or where it was put
     */
    public Vec3 pos() {
        return follow != null ? follow.position().add(offset) : pos;
    }

    public Spec spec() {
        return spec;
    }

    public HologramPanel panel() {
        return spec.panel();
    }

    public int version() {
        return version;
    }

    public boolean isSaved() {
        return saved;
    }

    public boolean isRemoved() {
        return removed;
    }

    @Nullable
    public Entity following() {
        return follow;
    }

    public Vec3 offset() {
        return offset;
    }

    @Nullable
    public ResourceLocation template() {
        return template;
    }

    /**
     * the player (and condition) it belongs to, if any
     */
    public Optional<Tie> tie() {
        return Optional.ofNullable(tie);
    }

    /**
     * the players it is shown to right now
     */
    public Set<UUID> viewers() {
        return Set.copyOf(viewing.keySet());
    }

    public UiActions.Context context() {
        return new UiActions.Context(level, follow, null, null, id);
    }

    public boolean canSee(ServerPlayer player) {
        if (player.level() != level || removed) return false;
        return player.position().distanceToSqr(pos()) <= spec.range() * spec.range() && spec.viewers().test(player, context());
    }

    public boolean canUse(ServerPlayer player) {
        return spec.interactors().test(player, context());
    }

    /**
     * the buttons this player can't press: not an interactor, or a button's conditions fail
     */
    public List<String> disabledFor(ServerPlayer player) {
        boolean user = canUse(player);
        List<String> disabled = new ArrayList<>();
        for (HologramPanel.Button button : spec.panel().buttons()) {
            if (!user || !UiActions.testAll(button.conditions(), player, context())) disabled.add(button.id());
        }
        return disabled;
    }

    // ---- changing it ----

    /**
     * New content, built like at(...).content(...). Every viewer gets it immediately
     */
    public Hologram update(Consumer<HologramPanel.Builder> content) {
        HologramPanel.Built built = HologramPanel.build(content);
        return change(spec.withPanel(built.panel()), built.handlers());
    }

    public Hologram panel(HologramPanel panel) {
        return change(spec.withPanel(panel), handlers);
    }

    /**
     * swaps the part with this key (a text, item or bar key, or a button id)
     */
    public Hologram set(String key, HologramPanel.Part part) {
        return panel(spec.panel().with(key, part));
    }

    public Hologram text(String key, Component text) {
        return set(key, new HologramPanel.Text(key, text));
    }

    /**
     * a keyed bar's value, its other settings kept
     */
    public Hologram bar(String key, float value) {
        if (!(spec.panel().find(key) instanceof HologramPanel.Bar bar)) return this;
        return set(key, new HologramPanel.Bar(key, bar.text(), value, bar.max(), bar.color()));
    }

    /**
     * New data for its custom UI. Every viewer gets it immediately. Saved holograms keep it.
     */
    public Hologram data(CompoundTag data) {
        return change(spec.withData(data.copy()), handlers);
    }

    /**
     * Data for one viewer alone, laid over the hologram's own data on their client (a shop showing that player's
     * coins). An empty tag clears it. Kept in memory only, and dropped when the player leaves the server.
     */
    public Hologram data(ServerPlayer player, CompoundTag data) {
        if (removed) return this;
        if (data.isEmpty()) personal.remove(player.getUUID());
        else personal.put(player.getUUID(), data.copy());
        Holograms.resend(this, player);
        return this;
    }

    // what this player's client gets as the data tag
    CompoundTag dataFor(UUID player) {
        CompoundTag own = personal.get(player);
        return own == null ? spec.data() : spec.data().copy().merge(own);
    }

    void forget(UUID player) {
        personal.remove(player);
    }

    public Hologram viewers(Audience viewers) {
        return change(spec.withAudiences(viewers, spec.interactors()), handlers);
    }

    public Hologram interactors(Audience interactors) {
        return change(spec.withAudiences(spec.viewers(), interactors), handlers);
    }

    public Hologram move(Vec3 pos) {
        if (follow != null) return this;
        this.pos = pos;
        version++;
        Holograms.changed(this);
        return this;
    }

    /**
     * from now on it turns to face each viewer
     */
    public Hologram billboard() {
        return change(spec.withFacing(true, spec.yaw()), handlers);
    }

    /**
     * from now on it stays put, its front facing this way (degrees, 0 = south, 90 = west, like a player's yaw)
     */
    public Hologram facing(float yaw) {
        return change(spec.withFacing(false, yaw), handlers);
    }

    public Hologram facing(Direction direction) {
        return facing(direction.toYRot());
    }

    /**
     * stays put, its front turned toward a point (a player's eyes, a door)
     */
    public Hologram facingToward(Vec3 point) {
        return facing(yawToward(pos(), point, spec.yaw()));
    }

    // The yaw that turns a front at `from` toward `to`. `fallback` when `to` is straight above or below
    static float yawToward(Vec3 from, Vec3 to, float fallback) {
        double dx = to.x - from.x, dz = to.z - from.z;
        if (dx * dx + dz * dz < 1e-8) return fallback;
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    }

    private Hologram change(Spec next, Map<String, HologramPanel.Handler> nextHandlers) {
        if (removed) return this;
        if (saved) checkSaveable(next, nextHandlers, follow, onRemove);
        spec = next;
        handlers = Map.copyOf(nextHandlers);
        version++;
        Holograms.changed(this);
        return this;
    }

    // ---- timers ----

    /**
     * It is removed this many ticks from now (0 = never). Viewers see no change.
     */
    public Hologram lifetime(int ticks) {
        if (removed) return this;
        spec = spec.withTimers(ticks, spec.idleTimeout());
        expiresAt = ticks > 0 ? level.getGameTime() + ticks : NEVER;
        Holograms.timersChanged(this);
        return this;
    }

    /**
     * It is removed once this many ticks pass without a button press, counted from now (0 = never)
     */
    public Hologram idleTimeout(int ticks) {
        if (removed) return this;
        spec = spec.withTimers(spec.lifetime(), ticks);
        lastUsed = level.getGameTime();
        Holograms.timersChanged(this);
        return this;
    }

    /**
     * Starts the idle timeout again, as a button press does. For interaction the hologram can't see
     */
    public Hologram keepAlive() {
        if (removed) return this;
        lastUsed = level.getGameTime();
        Holograms.timersChanged(this);
        return this;
    }

    /**
     * ticks until a timer takes it away, or -1 when no timer will
     */
    public int expiresIn() {
        long now = level.getGameTime();
        long at = expiresAt;
        if (spec.idleTimeout() > 0) at = Math.min(at, lastUsed + spec.idleTimeout());
        return at == NEVER ? -1 : (int) Math.max(0, Math.min(Integer.MAX_VALUE, at - now));
    }

    long expiresAt() {
        return expiresAt;
    }

    long lastUsed() {
        return lastUsed;
    }

    // a saved hologram's clocks, as they were when it was saved
    void restoreClocks(long expiresAt, long lastUsed) {
        this.expiresAt = expiresAt;
        this.lastUsed = lastUsed;
    }

    void used() {
        lastUsed = level.getGameTime();
    }

    // Why it should be removed now, or null to keep it: its entity, its timers, then its tie
    @Nullable
    RemoveReason dueToGo() {
        if (follow != null && (follow.isRemoved() || !follow.isAlive() || follow.level() != level)) return RemoveReason.ENTITY_GONE;
        long now = level.getGameTime();
        if (now >= expiresAt) return RemoveReason.EXPIRED;
        if (spec.idleTimeout() > 0 && now - lastUsed >= spec.idleTimeout()) return RemoveReason.IDLE;
        if (tie == null) return null;
        ServerPlayer owner = Holograms.findPlayer(level.getServer(), tie.player());
        if (owner == null) return saved ? null : RemoveReason.OWNER_GONE;
        if (tie.condition().isEmpty()) return null;
        return UiActions.test(tie.condition().get(), owner, context()) ? null : RemoveReason.CONDITION_FAILED;
    }

    // ---- going ----

    public void remove() {
        Holograms.remove(this, RemoveReason.REMOVED);
    }

    void markRemoved() {
        removed = true;
    }

    void runRemoveListener(RemoveReason reason) {
        if (onRemove != null) onRemove.removed(this, reason);
    }

    @Nullable
    HologramPanel.Handler handler(String button) {
        return handlers.get(button);
    }

    Optional<Spec> saveableSpec() {
        return saved ? Optional.of(spec) : Optional.empty();
    }
}
