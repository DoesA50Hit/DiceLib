package org._50dozenhits.dice_library.state;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org._50dozenhits.dice_library.DiceLib;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * What an ability keeps per caster while it lasts and never saves, such as the chain it is
 * holding. It empties itself when the caster dies, leaves the level (logs out, changes dimension, is
 * unloaded or removed), is cleared with clearAll, or the server stops, running onClear on what it drops so the
 * props it held can be taken away. Server side. Make one per kind of thing, as a static field:
 *
 * <pre>{@code
 * private static final CasterState<ChainEntity> TETHERS = CasterState.create((caster, chain) -> chain.retract());
 * }</pre>
 *
 * <p>A count that has to survive a save (how many blades are out) belongs in saved data instead, such as an attachment.
 */
public final class CasterState<T> {
    private static final List<CasterState<?>> ALL = new ArrayList<>();

    private final Map<UUID, T> values = new HashMap<>();
    @Nullable
    private final BiConsumer<UUID, T> onClear;

    private CasterState(@Nullable BiConsumer<UUID, T> onClear) {
        this.onClear = onClear;
    }

    public static <T> CasterState<T> create() {
        return create(null);
    }

    /**
     * onClear gets the caster's id and the value dropped, when the store drops it by itself or through remove
     */
    public static <T> CasterState<T> create(@Nullable BiConsumer<UUID, T> onClear) {
        CasterState<T> state = new CasterState<>(onClear);
        synchronized (ALL) {
            ALL.add(state);
        }
        return state;
    }

    @Nullable
    public T get(Entity caster) {
        return values.get(caster.getUUID());
    }

    public T getOrCreate(Entity caster, Supplier<T> create) {
        return values.computeIfAbsent(caster.getUUID(), id -> create.get());
    }

    public boolean has(Entity caster) {
        return values.containsKey(caster.getUUID());
    }

    /**
     * Sets it. A value already there is dropped through onClear first.
     */
    public void put(Entity caster, T value) {
        T old = values.put(caster.getUUID(), value);
        if (old != null && old != value && onClear != null) onClear.accept(caster.getUUID(), old);
    }

    /**
     * Takes it out and runs onClear on it. Null when there was none.
     */
    @Nullable
    public T remove(Entity caster) {
        return remove(caster.getUUID(), true);
    }

    /**
     * Takes it out without onClear: the ability is handing it on, not ending it.
     */
    @Nullable
    public T take(Entity caster) {
        return remove(caster.getUUID(), false);
    }

    public Map<UUID, T> view() {
        return Map.copyOf(values);
    }

    @Nullable
    private T remove(UUID id, boolean clear) {
        T value = values.remove(id);
        if (value != null && clear && onClear != null) onClear.accept(id, value);
        return value;
    }

    /**
     * Drops everything every store holds for this caster. The events below call it. Call it yourself for a reset of
     * your own (a respec, a class change).
     */
    public static void clearAll(Entity caster) {
        List<CasterState<?>> states;
        synchronized (ALL) {
            states = List.copyOf(ALL);
        }
        for (CasterState<?> state : states) state.remove(caster.getUUID(), true);
    }

    @EventBusSubscriber(modid = DiceLib.MODID)
    static final class Events {
        // last, so a death another listener cancels (a revive) clears nothing
        @SubscribeEvent(priority = EventPriority.LOWEST)
        static void onDeath(LivingDeathEvent event) {
            LivingEntity entity = event.getEntity();
            if (!entity.level().isClientSide()) clearAll(entity);
        }

        @SubscribeEvent
        static void onLeave(EntityLeaveLevelEvent event) {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity) clearAll(event.getEntity());
        }

        @SubscribeEvent
        static void onStopped(ServerStoppedEvent event) {
            List<CasterState<?>> states;
            synchronized (ALL) {
                states = List.copyOf(ALL);
            }
            for (CasterState<?> state : states) {
                for (UUID id : List.copyOf(state.values.keySet())) state.remove(id, true);
            }
        }
    }
}
