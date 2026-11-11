package org._50dozenhits.dice_library.prop;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.visual.Fx;
import org._50dozenhits.dice_library.registry.DiceEntities;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * A line drawn between two ends, drawn as a chain, a string of glyphs or a ribbon (TetherLook). Each
 * end is an entity (followed as it moves) or a fixed point. It can't be hit and is never saved, unless it is made
 * breakable: then it has barrier health, and anyone not allied with its owner can hit and break it. Ways it moves:
 * - it shoots out from its start over {@code extend} ticks, and can pull back in again (a lash);
 * - spinning: its far end circles the start (a wrecking ball's swing);
 * - its far end can be moved by the server every tick (to(point) each tick: a dragged weight).
 * It is removed when an entity at either end is. {@code life} counts down to it going. -1 lasts.
 *
 * <pre>{@code
 * Tether.spawn(level, TetherLook.CHAIN, BodyPoints.hand(caster, true)).from(caster, true).to(target).lash(4, 6, 4);
 * Tether.spawn(level, TetherLook.RIBBON, start).from(target, false).to(caster).ribbon(0.3f, Tether.Flow.IN, 0xFFFFFF).life(40);
 * }</pre>
 */
public class Tether extends Entity {
    /**
     * which way a ribbon's texture runs: toward the far end, toward the start (drawn back to the caster), or still
     */
    public enum Flow {
        OUT, IN, STILL
    }

    private static final EntityDataAccessor<String> LOOK = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.STRING);
    // a block hung on the far end (air for none), like a heavy core on a flail
    private static final EntityDataAccessor<BlockState> END = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Integer> FROM = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> FROM_POINT = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Boolean> FROM_HAND = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> TO = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> TO_POINT = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Float> SAG = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPIN_RADIUS = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPIN_SPEED = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.FLOAT);
    // game time it started shooting out, and how long that takes
    private static final EntityDataAccessor<Integer> BORN = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EXTEND = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);
    // game time it started pulling back in (-1 not yet), and how long that takes
    private static final EntityDataAccessor<Integer> RETRACT_AT = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> RETRACT = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);
    // barrier health. A max of 0 isn't breakable and can't be hit
    private static final EntityDataAccessor<Float> HEALTH = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> MAX_HEALTH = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.FLOAT);
    // a ribbon's width in blocks, flow and tint (RGB)
    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> FLOW = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> TINT = SynchedEntityData.defineId(Tether.class, EntityDataSerializers.INT);

    // server only
    @Nullable
    private LivingEntity owner;
    @Nullable
    private Runnable onBreak;
    private int life = -1;
    private int retractIn = -1;

    public Tether(EntityType<? extends Tether> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public static Tether spawn(ServerLevel level, TetherLook look, Vec3 at) {
        Tether chain = new Tether(DiceEntities.TETHER.get(), level);
        chain.entityData.set(LOOK, look.id().toString());
        chain.entityData.set(BORN, (int) level.getGameTime());
        chain.entityData.set(FROM_POINT, at.toVector3f());
        chain.entityData.set(TO_POINT, at.toVector3f());
        chain.setPos(at);
        level.addFreshEntity(chain);
        return chain;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LOOK, TetherLook.CHAIN.id().toString());
        builder.define(END, Blocks.AIR.defaultBlockState());
        builder.define(FROM, -1);
        builder.define(FROM_POINT, new Vector3f());
        builder.define(FROM_HAND, false);
        builder.define(TO, -1);
        builder.define(TO_POINT, new Vector3f());
        builder.define(SAG, 0f);
        builder.define(SPIN_RADIUS, 0f);
        builder.define(SPIN_SPEED, 0f);
        builder.define(BORN, 0);
        builder.define(EXTEND, 0);
        builder.define(RETRACT_AT, -1);
        builder.define(RETRACT, 4);
        builder.define(HEALTH, 0f);
        builder.define(MAX_HEALTH, 0f);
        builder.define(WIDTH, 0.25f);
        builder.define(FLOW, (byte) Flow.OUT.ordinal());
        builder.define(TINT, 0xFFFFFF);
    }

    // --- setting it up (server) ---

    /**
     * starts at {@code entity}, from its right hand when {@code hand}, else its middle
     */
    public Tether from(Entity entity, boolean hand) {
        entityData.set(FROM, entity.getId());
        entityData.set(FROM_HAND, hand);
        return this;
    }

    public Tether from(Vec3 point) {
        entityData.set(FROM, -1);
        entityData.set(FROM_POINT, point.toVector3f());
        return this;
    }

    /**
     * ends at {@code entity}'s middle
     */
    public Tether to(Entity entity) {
        entityData.set(TO, entity.getId());
        return this;
    }

    public Tether to(Vec3 point) {
        entityData.set(TO, -1);
        entityData.set(TO_POINT, point.toVector3f());
        return this;
    }

    /**
     * hangs {@code block} on the far end, turning slowly
     */
    public Tether end(BlockState block) {
        entityData.set(END, block);
        return this;
    }

    /**
     * for a RIBBON look: {@code width} blocks wide, its texture running {@code flow}, tinted {@code tint} (RGB, white for none)
     */
    public Tether ribbon(float width, Flow flow, int tint) {
        entityData.set(WIDTH, width);
        entityData.set(FLOW, (byte) flow.ordinal());
        entityData.set(TINT, tint);
        return this;
    }

    /**
     * how far the middle hangs down, in blocks for each block of length
     */
    public Tether sag(float sag) {
        entityData.set(SAG, sag);
        return this;
    }

    /**
     * the far end circles the start {@code radius} out, {@code speed} radians a tick, instead of going to its own end
     */
    public Tether spin(float radius, float speed) {
        entityData.set(SPIN_RADIUS, radius);
        entityData.set(SPIN_SPEED, speed);
        return this;
    }

    /**
     * shoots out from its start over {@code ticks}
     */
    public Tether extend(int ticks) {
        entityData.set(BORN, (int) level().getGameTime());
        entityData.set(EXTEND, ticks);
        return this;
    }

    /**
     * A lash: shoots out over {@code extend} ticks, stays {@code hold}, pulls back in over {@code retract} and is removed.
     */
    public Tether lash(int extend, int hold, int retract) {
        extend(extend);
        entityData.set(RETRACT, retract);
        retractIn = extend + hold;
        life = -1;
        return this;
    }

    /**
     * removed after {@code ticks}, pulling back in over its last few. -1 lasts until taken away
     */
    public Tether life(int ticks) {
        life = ticks;
        return this;
    }

    /**
     * Pulls back in now and is removed.
     */
    public void retract() {
        if (entityData.get(RETRACT_AT) >= 0) return;
        entityData.set(RETRACT_AT, (int) level().getGameTime());
        life = entityData.get(RETRACT);
        retractIn = -1;
    }

    /**
     * Breakable: {@code health} of barrier health, hit by anyone not allied with {@code owner}. {@code onBreak} runs when it breaks.
     */
    public Tether breakable(LivingEntity owner, float health, @Nullable Runnable onBreak) {
        this.owner = owner;
        this.onBreak = onBreak;
        entityData.set(MAX_HEALTH, health);
        entityData.set(HEALTH, health);
        return this;
    }

    // --- what it looks like ---

    public TetherLook getLook() {
        ResourceLocation id = ResourceLocation.tryParse(entityData.get(LOOK));
        return id == null ? TetherLook.CHAIN : TetherLook.get(id);
    }

    public BlockState getEnd() {
        return entityData.get(END);
    }

    public float getWidth() {
        return entityData.get(WIDTH);
    }

    public Flow getFlow() {
        return Flow.values()[entityData.get(FLOW)];
    }

    public int getTint() {
        return entityData.get(TINT);
    }

    public float getSag() {
        return entityData.get(SAG);
    }

    public float getHealth() {
        return entityData.get(HEALTH);
    }

    public float getMaxHealth() {
        return entityData.get(MAX_HEALTH);
    }

    @Nullable
    public Entity fromEntity() {
        int id = entityData.get(FROM);
        return id < 0 ? null : level().getEntity(id);
    }

    @Nullable
    public Entity toEntity() {
        int id = entityData.get(TO);
        return id < 0 ? null : level().getEntity(id);
    }

    /**
     * where the chain starts at {@code partialTick}
     */
    public Vec3 start(float partialTick) {
        Entity from = fromEntity();
        if (from == null) return point(FROM_POINT);
        Vec3 at = from.getPosition(partialTick);
        if (!entityData.get(FROM_HAND)) return at.add(0, from.getBbHeight() * 0.5, 0);
        float yaw = (from instanceof LivingEntity living ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot) : from.getYRot()) * Mth.DEG_TO_RAD;
        return at.add(-Math.cos(yaw) * 0.38, from.getBbHeight() * 0.58, -Math.sin(yaw) * 0.38);
    }

    /**
     * where the far end would be fully out, at {@code time} (game time plus partial tick)
     */
    public Vec3 finish(double time, float partialTick) {
        float radius = entityData.get(SPIN_RADIUS);
        if (radius > 0) {
            double angle = time * entityData.get(SPIN_SPEED);
            return start(partialTick).add(Math.cos(angle) * radius, -0.2, Math.sin(angle) * radius);
        }
        Entity to = toEntity();
        return to == null ? point(TO_POINT) : to.getPosition(partialTick).add(0, to.getBbHeight() * 0.5, 0);
    }

    /**
     * how far out it is at {@code time}, 0 to 1
     */
    public double reach(double time) {
        int extend = entityData.get(EXTEND);
        double out = extend <= 0 ? 1 : Mth.clamp((time - entityData.get(BORN)) / extend, 0, 1);
        int retractAt = entityData.get(RETRACT_AT);
        if (retractAt >= 0) out = Math.min(out, 1 - Mth.clamp((time - retractAt) / Math.max(1, entityData.get(RETRACT)), 0, 1));
        return out;
    }

    /**
     * the far end as drawn at {@code time}
     */
    public Vec3 tip(double time, float partialTick) {
        Vec3 start = start(partialTick);
        return start.lerp(finish(time, partialTick), reach(time));
    }

    private Vec3 point(EntityDataAccessor<Vector3f> accessor) {
        Vector3f point = entityData.get(accessor);
        return new Vec3(point.x, point.y, point.z);
    }

    // --- every tick ---

    @Override
    public void tick() {
        super.tick();
        if ((entityData.get(FROM) >= 0 && !alive(fromEntity())) || (entityData.get(TO) >= 0 && !alive(toEntity()))) {
            if (!level().isClientSide()) discard();
            return;
        }
        // the hitbox sits in the middle of the chain, which is where a breakable one is hit
        Vec3 middle = start(1).lerp(tip(level().getGameTime(), 1), 0.5);
        setPos(middle.x, middle.y - getBbHeight() / 2, middle.z);
        if (level().isClientSide()) return;
        if (retractIn >= 0 && retractIn-- == 0) retract();
        if (life >= 0) {
            if (life == entityData.get(RETRACT) && entityData.get(RETRACT_AT) < 0) retract();
            if (life-- == 0) discard();
        }
    }

    private static boolean alive(@Nullable Entity entity) {
        return entity != null && entity.isAlive();
    }

    /**
     * Covers both ends, so the chain is drawn whenever any of it is in view.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        Vec3 start = start(1);
        return new AABB(start, tip(level().getGameTime(), 1)).inflate(1.5);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide() || getMaxHealth() <= 0 || isRemoved()) return false;
        Entity attacker = source.getEntity();
        if (attacker != null && owner != null && (attacker == owner || Targets.isAlly(owner, attacker))) return false;
        float left = getHealth() - amount;
        entityData.set(HEALTH, Math.max(0, left));
        ServerLevel level = (ServerLevel) level();
        level.playSound(null, blockPosition(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 1, 0.8f);
        if (left <= 0) {
            ResourceLocation fx = getLook().breakFx();
            if (fx != null) Fx.at(level, fx, position().add(0, getBbHeight() / 2, 0), 1, Fx.Fallback.burst(ParticleTypes.CRIT, 0.5f, 12));
            else level.sendParticles(ParticleTypes.CRIT, getX(), getY() + getBbHeight() / 2, getZ(), 12, 0.3, 0.3, 0.3, 0.2);
            level.playSound(null, blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.2f, 0.7f);
            Runnable handler = onBreak;
            onBreak = null;
            discard();
            if (handler != null) handler.run();
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return getMaxHealth() > 0 && !isRemoved();
    }

    @Override
    public boolean isAttackable() {
        return isPickable();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96 * 96;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
