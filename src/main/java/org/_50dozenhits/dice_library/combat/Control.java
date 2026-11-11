package org._50dozenhits.dice_library.combat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.Util;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.registry.DiceAttachments;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Takes a mob over for a while, for a charm or a mind control. It works on any Mob, tamable or not, and
 * leaves vanilla taming alone, so when it ends the mob is exactly what it was. Server side.
 *
 * <pre>{@code
 * // the zombie fights for the caster for 30 seconds
 * if (!Control.of(caster, zombie).life(600).start()) caster.sendSystemMessage(Component.literal("It resists."));
 * }</pre>
 *
 * <p>While controlled, a mob drops its target, never targets its controller or the controller's allies, goes after
 * whoever hurt its controller or whoever its controller hurt, and walks after its controller when it has nothing to
 * fight. Targets counts it as its controller's, so abilities treat it as an ally. Mobs that can't attack
 * only follow. Mobs that pick targets through a brain (piglins, wardens) are stopped from attacking their controller
 * but may not take up the controller's fights.
 *
 * <p>Refused for bosses (the c:bosses entity type tag) unless allowBosses() is called, for entity types in the
 * dicelib:no_tame tag, and for any entity carrying the tag "noTame" (/tag ... add noTame). The last two can't be
 * bypassed. Control is saved with the mob and counts game time. An onEnd listener is not saved, so after a restart
 * only ControlEvent.End reports the end.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class Control {
    /**
     * entity types nothing can take control of
     */
    public static final TagKey<EntityType<?>> NO_TAME = TagKey.create(Registries.ENTITY_TYPE, DiceLib.id("no_tame"));
    /**
     * the tag (as /tag gives) on one entity that nothing can take control of
     */
    public static final String NO_TAME_TAG = "noTame";
    // how far from its controller an idle mob lets itself get before it walks back, and how often it thinks about it
    private static final double FOLLOW_FROM = 8;
    private static final int THINK_EVERY = 20;
    // ticks an attack on or by the controller stays worth acting on
    private static final int GRUDGE = 200;

    /**
     * Who controls a mob and until which game time. NONE means nobody.
     */
    public record Held(UUID owner, long until) {
        public static final Held NONE = new Held(Util.NIL_UUID, 0);
        public static final Codec<Held> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Held::owner),
                Codec.LONG.fieldOf("until").forGetter(Held::until)
        ).apply(i, Held::new));
    }

    /**
     * Why control ended
     */
    public enum End {
        /**
         * its time ran out
         */
        EXPIRED,
        /**
         * release was called
         */
        RELEASED,
        /**
         * someone else took control
         */
        REPLACED,
        /**
         * the mob died or was removed (despawned, discarded). mob.isDeadOrDying() tells the two apart.
         */
        GONE
    }

    // listeners for the mobs controlled since the server started
    private static final Map<UUID, BiConsumer<Mob, End>> ON_END = new HashMap<>();

    /**
     * Control to set up. start() takes it.
     */
    public static Taking of(LivingEntity owner, Mob mob) {
        return new Taking(owner, mob);
    }

    public static final class Taking {
        private final LivingEntity owner;
        private final Mob mob;
        private int ticks = 600;
        private boolean bosses;
        @Nullable
        private BiConsumer<Mob, End> onEnd;

        private Taking(LivingEntity owner, Mob mob) {
            this.owner = owner;
            this.mob = mob;
        }

        /**
         * how long it lasts (600, half a minute, by default)
         */
        public Taking life(int ticks) {
            this.ticks = Math.max(1, ticks);
            return this;
        }

        /**
         * lets it work on bosses. dicelib:no_tame and the noTame tag still refuse.
         */
        public Taking allowBosses() {
            this.bosses = true;
            return this;
        }

        /**
         * runs once when the control ends, with the reason. Not kept through a restart.
         */
        public Taking onEnd(BiConsumer<Mob, End> onEnd) {
            this.onEnd = onEnd;
            return this;
        }

        /**
         * Takes control. False when the mob can't be controlled (canControl) or a ControlEvent.Start listener
         * cancelled it. A mob someone else controls changes hands.
         */
        public boolean start() {
            if (!(mob.level() instanceof ServerLevel level) || !canControl(owner, mob, bosses)) return false;
            if (NeoForge.EVENT_BUS.post(new ControlEvent.Start(mob, owner, ticks)).isCanceled()) return false;
            if (isControlled(mob)) end(mob, End.REPLACED);
            mob.setData(DiceAttachments.CONTROL, new Held(owner.getUUID(), level.getGameTime() + ticks));
            if (onEnd != null) ON_END.put(mob.getUUID(), onEnd);
            calm(mob);
            return true;
        }
    }

    /**
     * Whether {@code owner} could take control of {@code mob}: it is alive, isn't the owner, isn't in dicelib:no_tame,
     * doesn't carry the noTame tag, and isn't a boss unless {@code bosses} is true.
     */
    public static boolean canControl(LivingEntity owner, Mob mob, boolean bosses) {
        if (mob == owner || !mob.isAlive() || mob.getType().is(NO_TAME) || mob.getTags().contains(NO_TAME_TAG)) return false;
        return bosses || !mob.getType().is(Tags.EntityTypes.BOSSES);
    }

    public static boolean isControlled(Entity entity) {
        return entity instanceof Mob mob && mob.hasData(DiceAttachments.CONTROL) && !mob.getData(DiceAttachments.CONTROL).equals(Held.NONE);
    }

    /**
     * the id of whoever controls the entity, or null
     */
    @Nullable
    public static UUID ownerId(Entity entity) {
        return isControlled(entity) ? entity.getData(DiceAttachments.CONTROL).owner() : null;
    }

    /**
     * whoever controls the entity, when they are in the same level. Null for nobody, or for a controller who is
     * elsewhere or offline
     */
    @Nullable
    public static LivingEntity owner(Entity entity) {
        UUID id = ownerId(entity);
        return id != null && entity.level() instanceof ServerLevel level && level.getEntity(id) instanceof LivingEntity owner ? owner : null;
    }

    /**
     * ticks of control left, or 0
     */
    public static int remaining(Entity entity) {
        if (!isControlled(entity)) return 0;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, entity.getData(DiceAttachments.CONTROL).until() - entity.level().getGameTime()));
    }

    /**
     * the loaded mobs in the owner's level that it controls
     */
    public static List<Mob> controlledBy(LivingEntity owner) {
        List<Mob> found = new ArrayList<>();
        if (!(owner.level() instanceof ServerLevel level)) return found;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && owner.getUUID().equals(ownerId(mob))) found.add(mob);
        }
        return found;
    }

    /**
     * Ends the control now. False when nobody controlled it.
     */
    public static boolean release(Mob mob) {
        if (!isControlled(mob)) return false;
        end(mob, End.RELEASED);
        return true;
    }

    private static void end(Mob mob, End reason) {
        LivingEntity owner = owner(mob);
        mob.removeData(DiceAttachments.CONTROL);
        if (reason != End.GONE) calm(mob);
        BiConsumer<Mob, End> listener = ON_END.remove(mob.getUUID());
        if (listener != null) listener.accept(mob, reason);
        NeoForge.EVENT_BUS.post(new ControlEvent.End(mob, owner, reason));
    }

    // drops whoever it was after, for goal mobs and brain mobs alike
    private static void calm(Mob mob) {
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT);
        mob.getNavigation().stop();
    }

    @SubscribeEvent
    static void onTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide || !isControlled(mob)) return;
        if (remaining(mob) <= 0) {
            end(mob, End.EXPIRED);
            return;
        }
        if (mob.tickCount % THINK_EVERY != 0) return;
        LivingEntity owner = owner(mob);
        if (owner == null || !owner.isAlive()) return;
        LivingEntity target = mob.getTarget();
        if (target != null && (!target.isAlive() || Targets.isAlly(owner, target))) {
            mob.setTarget(null);
            target = null;
        }
        if (target == null && fights(mob)) {
            LivingEntity foe = foe(owner, mob);
            if (foe != null) {
                mob.setTarget(foe);
                return;
            }
        }
        if (target == null && mob.distanceToSqr(owner) > FOLLOW_FROM * FOLLOW_FROM) mob.getNavigation().moveTo(owner, 1.2);
    }

    private static boolean fights(Mob mob) {
        return mob.getAttribute(Attributes.ATTACK_DAMAGE) != null || mob instanceof RangedAttackMob;
    }

    // whoever last hurt the controller, else whoever the controller last hurt, when that was recent and they aren't friends
    @Nullable
    private static LivingEntity foe(LivingEntity owner, Mob mob) {
        LivingEntity attacker = owner.getLastHurtByMob();
        if (worthFighting(owner, mob, attacker) && owner.tickCount - owner.getLastHurtByMobTimestamp() < GRUDGE) return attacker;
        LivingEntity victim = owner.getLastHurtMob();
        if (worthFighting(owner, mob, victim) && owner.tickCount - owner.getLastHurtMobTimestamp() < GRUDGE) return victim;
        return null;
    }

    private static boolean worthFighting(LivingEntity owner, Mob mob, @Nullable LivingEntity other) {
        return other != null && other != mob && other.level() == mob.level() && Targets.targetable(other) && !Targets.isAlly(owner, other);
    }

    @SubscribeEvent
    static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target == null || !isControlled(event.getEntity())) return;
        UUID ownerId = ownerId(event.getEntity());
        LivingEntity owner = owner(event.getEntity());
        if (target.getUUID().equals(ownerId) || owner != null && Targets.isAlly(owner, target)) event.setCanceled(true);
    }

    // last, so a death another mod cancels doesn't end it
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && !mob.level().isClientSide && isControlled(mob)) end(mob, End.GONE);
    }

    // removed for good without dying. One that only unloads keeps its control, which is saved with it.
    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        Entity.RemovalReason removal = mob.getRemovalReason();
        if (removal != null && removal.shouldDestroy() && isControlled(mob)) end(mob, End.GONE);
    }

    @SubscribeEvent
    static void onStopped(ServerStoppedEvent event) {
        ON_END.clear();
    }

    /**
     * Server side, on NeoForge.EVENT_BUS. getEntity() is the mob.
     */
    public abstract static class ControlEvent extends LivingEvent {
        @Nullable
        private final LivingEntity owner;

        protected ControlEvent(Mob mob, @Nullable LivingEntity owner) {
            super(mob);
            this.owner = owner;
        }

        public Mob getMob() {
            return (Mob) getEntity();
        }

        /**
         * the controller. On End it is null when they are in another level or offline.
         */
        @Nullable
        public LivingEntity getOwner() {
            return owner;
        }

        /**
         * Before control is taken, after the mob passed canControl. Cancel to refuse (an immunity, a resist roll)
         */
        public static class Start extends ControlEvent implements ICancellableEvent {
            private final int ticks;

            public Start(Mob mob, LivingEntity owner, int ticks) {
                super(mob, owner);
                this.ticks = ticks;
            }

            public int getTicks() {
                return ticks;
            }
        }

        /**
         * After control ended, with the reason
         */
        public static class End extends ControlEvent {
            private final Control.End reason;

            public End(Mob mob, @Nullable LivingEntity owner, Control.End reason) {
                super(mob, owner);
                this.reason = reason;
            }

            public Control.End getReason() {
                return reason;
            }
        }
    }

    private Control() {
    }
}
