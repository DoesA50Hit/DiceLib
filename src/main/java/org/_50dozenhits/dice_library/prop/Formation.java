package org._50dozenhits.dice_library.prop;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.state.CasterState;
import org._50dozenhits.dice_library.visual.BodyPoints;
import org._50dozenhits.dice_library.visual.Fx;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The floating items one caster has out, kept in named groups: the "ready" set that waits around them (kept at a
 * count and in an arrangement every tick by keep), and sets held by an ability for a while (a shieldwall, a ring). Only
 * the look: how many blades a caster really has (and what a save must keep) belongs in the mod's own saved data (an
 * attachment, a counter on the item), which then tells keep how many to show. A caster's objects vanish on their own
 * when it dies or leaves, or when CasterState.clearAll runs for it. Server side. Make one per kind of set, as a static
 * field:
 *
 * <pre>{@code
 * private static final Formation SWORDS = Formation.of(caster -> new ItemStack(Items.IRON_SWORD));
 * // every tick, from a player tick listener: three swords fanned behind the back
 * SWORDS.keep(player, 3, (sword, slot, count) -> sword.fan(player, slot, count, 0.9f, 1.1f));
 * // an ability sends one
 * SWORDS.take(player).fly(player, aim.scale(1.6), 24, true, Targets.Relation.NON_ALLIES, onHit, 12);
 * }</pre>
 */
public final class Formation {
    public static final String READY = "ready";

    /**
     * how one object of a set of {@code count} sits, slot {@code slot}
     */
    @FunctionalInterface
    public interface Arrange {
        void place(FloatingItem object, int slot, int count);
    }

    private final Function<LivingEntity, ItemStack> item;
    private final CasterState<Map<String, List<FloatingItem>>> sets;
    @Nullable
    private ResourceLocation vanishFx;

    private Formation(Function<LivingEntity, ItemStack> item) {
        this.item = item;
        this.sets = CasterState.create((id, groups) -> groups.values().forEach(list -> list.forEach(this::vanish)));
    }

    /**
     * {@code item} is what a new object shows for that caster
     */
    public static Formation of(Function<LivingEntity, ItemStack> item) {
        return new Formation(item);
    }

    /**
     * played where an object vanishes (fallback: a puff of crit sparks)
     */
    public Formation vanishFx(ResourceLocation fx) {
        this.vanishFx = fx;
        return this;
    }

    /**
     * Tops the ready set up or trims it to {@code count} and places every one with {@code arrange}. Call it every tick (or whenever
     * the count or the caster's position changes); 0 vanishes the set.
     */
    public void keep(LivingEntity owner, int count, Arrange arrange) {
        List<FloatingItem> ready = group(owner, READY);
        ready.removeIf(o -> !o.isAlive() || o.level() != owner.level());
        while (ready.size() > count) vanish(ready.removeLast());
        while (ready.size() < count && owner.level() instanceof ServerLevel level) {
            ready.add(FloatingItem.spawn(level, item.apply(owner), BodyPoints.shoulder(owner, 1)));
        }
        for (int i = 0; i < ready.size(); i++) arrange.place(ready.get(i), i, ready.size());
    }

    /**
     * The last ready object, taken out of the set to be sent somewhere, or a new one by the right shoulder when the
     * set is empty. Keep the count it keeps showing in step yourself.
     */
    public FloatingItem take(LivingEntity owner) {
        List<FloatingItem> ready = group(owner, READY);
        while (!ready.isEmpty()) {
            FloatingItem object = ready.removeLast();
            if (object.isAlive() && object.level() == owner.level()) return object;
        }
        return spawn(owner, BodyPoints.shoulder(owner, 1));
    }

    /**
     * a new object of this set at {@code at}, in no group
     */
    public FloatingItem spawn(LivingEntity owner, Vec3 at) {
        return FloatingItem.spawn((ServerLevel) owner.level(), item.apply(owner), at);
    }

    /**
     * Keeps {@code objects} as the group {@code key} (replacing what was there, which vanishes), fading after {@code ticks} (-1 lasts).
     */
    public void hold(LivingEntity owner, String key, List<FloatingItem> objects, int ticks) {
        release(owner, key);
        if (ticks >= 0) objects.forEach(o -> o.life(ticks));
        group(owner, key).addAll(objects);
    }

    /**
     * the living objects of a group
     */
    public List<FloatingItem> held(LivingEntity owner, String key) {
        List<FloatingItem> list = group(owner, key);
        list.removeIf(o -> !o.isAlive());
        return List.copyOf(list);
    }

    /**
     * Takes up to {@code count} objects out of a group to be sent somewhere (they no longer vanish with it).
     */
    public List<FloatingItem> takeHeld(LivingEntity owner, String key, int count) {
        List<FloatingItem> list = group(owner, key);
        list.removeIf(o -> !o.isAlive());
        List<FloatingItem> taken = new ArrayList<>();
        while (taken.size() < count && !list.isEmpty()) taken.add(list.removeLast());
        return taken;
    }

    /**
     * vanishes a group
     */
    public void release(LivingEntity owner, String key) {
        Map<String, List<FloatingItem>> groups = sets.get(owner);
        if (groups == null) return;
        List<FloatingItem> list = groups.remove(key);
        if (list != null) list.forEach(this::vanish);
    }

    /**
     * vanishes every group
     */
    public void clear(LivingEntity owner) {
        sets.remove(owner);
    }

    /**
     * fades one out with a puff
     */
    public void vanish(FloatingItem object) {
        if (!object.isAlive()) return;
        if (object.level() instanceof ServerLevel level) {
            if (vanishFx != null) Fx.at(level, vanishFx, object.position(), 1, Fx.Fallback.burst(ParticleTypes.CRIT, 0.2f, 4));
            else level.sendParticles(ParticleTypes.CRIT, object.getX(), object.getY(), object.getZ(), 4, 0.1, 0.1, 0.1, 0.05);
        }
        object.discard();
    }

    private List<FloatingItem> group(LivingEntity owner, String key) {
        return sets.getOrCreate(owner, HashMap::new).computeIfAbsent(key, k -> new ArrayList<>());
    }
}
