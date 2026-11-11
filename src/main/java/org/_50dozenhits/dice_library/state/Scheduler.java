package org._50dozenhits.dice_library.state;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org._50dozenhits.dice_library.DiceLib;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;

/**
 * Runs code later on the server: once after a delay, or a number of times at an interval. Counted in server ticks, so
 * a paused singleplayer world pauses them too. Tasks live in memory only: a server stop drops them, so anything that
 * has to survive a restart needs its data saved somewhere and a check of its own. A task whose action throws is logged
 * and dropped. The other tasks and the server carry on.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class Scheduler {
    private static final List<Task> TASKS = new ArrayList<>();
    // New tasks wait here until the end of the tick they were made in, so after(20, ...) means 20 full ticks later.
    private static final List<Task> ADDED = new ArrayList<>();

    public static Task after(int ticks, Runnable action) {
        return add(new Task(null, Math.max(1, ticks), 1, run -> action.run()));
    }

    /**
     * Skipped (and cancelled) when the entity is gone by then: removed, unloaded, or dead for a living entity.
     */
    public static <T extends Entity> Task after(T entity, int ticks, Consumer<T> action) {
        return add(new Task(entity, Math.max(1, ticks), 1, run -> action.accept(entity)));
    }

    /**
     * {@code times} runs, the first one {@code interval} ticks from now. The int is the run number, from 0.
     */
    public static Task repeat(int interval, int times, IntConsumer action) {
        return add(new Task(null, Math.max(1, interval), times, action));
    }

    public static <T extends Entity> Task repeat(T entity, int interval, int times, ObjIntConsumer<T> action) {
        return add(new Task(entity, Math.max(1, interval), times, run -> action.accept(entity, run)));
    }

    private static Task add(Task task) {
        ADDED.add(task);
        return task;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        TASKS.removeIf(Task::tick);
        TASKS.addAll(ADDED);
        ADDED.clear();
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        TASKS.clear();
        ADDED.clear();
    }

    public static final class Task {
        @Nullable
        private final Entity entity;
        private final int interval;
        private final int times;
        private final IntConsumer action;
        private int countdown;
        private int runs;
        private boolean cancelled;

        private Task(@Nullable Entity entity, int interval, int times, IntConsumer action) {
            this.entity = entity;
            this.interval = interval;
            this.times = times;
            this.action = action;
            this.countdown = interval;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isDone() {
            return cancelled || runs >= times;
        }

        // true once it's finished, so the list drops it
        private boolean tick() {
            if (isDone()) return true;
            if (entity != null && (entity.isRemoved() || entity instanceof LivingEntity living && !living.isAlive())) {
                cancelled = true;
                return true;
            }
            if (--countdown > 0) return false;
            countdown = interval;
            try {
                action.accept(runs++);
            } catch (RuntimeException e) {
                DiceLib.LOGGER.error("A scheduled task threw on run {} and was dropped", runs - 1, e);
                cancelled = true;
            }
            return isDone();
        }
    }
}
