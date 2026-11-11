package org._50dozenhits.dice_library.prop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.registry.DiceEntities;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Any ItemStack floating in the world, drawn as its item model. It can't be hit and
 * is never saved. Four ways to be:
 * - ORBIT: circles its holder (or a fixed spot) at a radius and height. Both sides work the spot out from the game
 * <pre>{@code
 * time, so the circling is smooth.
 * }</pre>
 * - ROW: stands in a line in front of its holder, the way the holder faces, like a wall of shields.
 * - FAN: one of a fan behind its holder's back, tips up and out like wings.
 * - FREE: moved by the server: flies straight until it hits a target or a block or runs out of range, then sticks
 * <pre>{@code
 * where it stopped for a while (stay) and fades. With sticksToTarget() it stays in the entity it hit and goes where
 * that goes.
 * }</pre>
 * One with a holder is removed when its holder is. {@code life} counts down to fading for any of them. -1 lasts.
 * A Formation keeps a set of them per caster.
 *
 * <pre>{@code
 * FloatingItem sword = FloatingItem.spawn(level, new ItemStack(Items.IRON_SWORD), BodyPoints.shoulder(caster, 1));
 * sword.orbit(caster, Vec3.ZERO, 0, 3, 1.2f, 1.4f, 0.12f, FloatingItem.Tip.UP);
 * sword.fly(caster, aim.scale(1.6), 24, true, Targets.Relation.NON_ALLIES, (target, at) -> { ... }, 12);
 * }</pre>
 */
public class FloatingItem extends Entity {
    public enum Pose {
        ORBIT, ROW, FAN, FREE
    }

    /**
     * which way the tip points: UP and DOWN stand the object upright, TANGENT points it along its circle, ALONG
     * along its rotation (a flying or stuck object)
     */
    public enum Tip {
        UP, TANGENT, DOWN, ALONG
    }

    /**
     * what a flying object does when it stops. {@code target} is null for a block or the end of its range
     */
    public interface HitHandler {
        void hit(@Nullable LivingEntity target, Vec3 at);
    }

    private static final EntityDataAccessor<Integer> HOLDER = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.FLOAT);
    // degrees the item model is turned in its own plane before it is pointed: 45 stands a sword's diagonal sprite up
    private static final EntityDataAccessor<Float> SPRITE_TURN = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> FULL_BRIGHT = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> POSE = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> TIP = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> SLOT = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> SLOTS = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Vector3f> ANCHOR = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.VECTOR3);
    // a quick dart at an entity and back (a lent object striking), started at STRIKE_AT game time
    private static final EntityDataAccessor<Integer> STRIKE_TARGET = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STRIKE_AT = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.INT);
    public static final int STRIKE_TICKS = 6;
    // the entity a landed object is stuck in (-1 for none) and where on it, from its feet
    private static final EntityDataAccessor<Integer> STUCK_IN = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> STUCK_AT = SynchedEntityData.defineId(FloatingItem.class, EntityDataSerializers.VECTOR3);

    // server only: the flight
    @Nullable
    private LivingEntity owner;
    private double rangeLeft;
    private boolean hitsEntities;
    private Targets.Relation relation = Targets.Relation.NON_ALLIES;
    @Nullable
    private HitHandler onHit;
    private int stickTicks = 12;
    private boolean sticksToTarget;
    private int life = -1;

    public FloatingItem(EntityType<? extends FloatingItem> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /**
     * A new one at {@code at}, showing {@code item}, standing still until it is told to do something.
     */
    public static FloatingItem spawn(ServerLevel level, ItemStack item, Vec3 at) {
        FloatingItem object = new FloatingItem(DiceEntities.FLOATING_ITEM.get(), level);
        object.entityData.set(ITEM, item.copyWithCount(1));
        object.setPos(at);
        level.addFreshEntity(object);
        return object;
    }

    /**
     * drawn this much bigger (default 1.1)
     */
    public FloatingItem scale(float scale) {
        entityData.set(SCALE, scale);
        return this;
    }

    /**
     * the item model's turn in its own plane before it is pointed: 45 (default) for a sword or tool sprite, which is
     * drawn diagonally, 0 for an item drawn upright
     */
    public FloatingItem spriteTurn(float degrees) {
        entityData.set(SPRITE_TURN, degrees);
        return this;
    }

    /**
     * lit by the world instead of full bright (the default)
     */
    public FloatingItem worldLit() {
        entityData.set(FULL_BRIGHT, false);
        return this;
    }

    public FloatingItem item(ItemStack item) {
        entityData.set(ITEM, item.copyWithCount(1));
        return this;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(HOLDER, -1);
        builder.define(ITEM, ItemStack.EMPTY);
        builder.define(SCALE, 1.1f);
        builder.define(SPRITE_TURN, 45f);
        builder.define(FULL_BRIGHT, true);
        builder.define(POSE, (byte) Pose.FREE.ordinal());
        builder.define(TIP, (byte) Tip.ALONG.ordinal());
        builder.define(SLOT, (byte) 0);
        builder.define(SLOTS, (byte) 1);
        builder.define(RADIUS, 1f);
        builder.define(HEIGHT, 1f);
        builder.define(SPEED, 0f);
        builder.define(ANCHOR, new Vector3f());
        builder.define(STRIKE_TARGET, -1);
        builder.define(STRIKE_AT, 0);
        builder.define(STUCK_IN, -1);
        builder.define(STUCK_AT, new Vector3f());
    }

    // --- what it looks like ---

    public ItemStack getItem() {
        return entityData.get(ITEM);
    }

    public float getScale() {
        return entityData.get(SCALE);
    }

    public float getSpriteTurn() {
        return entityData.get(SPRITE_TURN);
    }

    public boolean isFullBright() {
        return entityData.get(FULL_BRIGHT);
    }

    public Pose getFloatPose() {
        return Pose.values()[entityData.get(POSE)];
    }

    public Tip getTip() {
        return Tip.values()[entityData.get(TIP)];
    }

    @Nullable
    public Entity getHolder() {
        int id = entityData.get(HOLDER);
        return id < 0 ? null : level().getEntity(id);
    }

    // --- setting it up (server) ---

    /**
     * Circles {@code holder} (null: the anchor) {@code radius} out and {@code height} up, slot {@code slot} of {@code slots} spread evenly,
     * turning {@code speed} radians a tick.
     */
    public FloatingItem orbit(@Nullable Entity holder, Vec3 anchor, int slot, int slots, float radius, float height, float speed, Tip tip) {
        entityData.set(HOLDER, holder == null ? -1 : holder.getId());
        entityData.set(ANCHOR, anchor.toVector3f());
        entityData.set(POSE, (byte) Pose.ORBIT.ordinal());
        entityData.set(TIP, (byte) tip.ordinal());
        setSlots(slot, slots);
        entityData.set(RADIUS, radius);
        entityData.set(HEIGHT, height);
        entityData.set(SPEED, speed);
        setDeltaMovement(Vec3.ZERO);
        return this;
    }

    /**
     * Stands in a line {@code distance} in front of {@code holder}, {@code height} up, slot {@code slot} of {@code slots} from its left.
     */
    public FloatingItem row(Entity holder, int slot, int slots, float distance, float height) {
        entityData.set(HOLDER, holder.getId());
        entityData.set(POSE, (byte) Pose.ROW.ordinal());
        entityData.set(TIP, (byte) Tip.UP.ordinal());
        setSlots(slot, slots);
        entityData.set(RADIUS, distance);
        entityData.set(HEIGHT, height);
        setDeltaMovement(Vec3.ZERO);
        return this;
    }

    /**
     * Fans out behind {@code holder}'s back from a point {@code height} up, slot {@code slot} of {@code slots}, hilt in and tip {@code reach} out.
     * Slot 0 is the middle and later slots go out a side at a time, so the outermost object is the last. A fan that
     * loses its last objects keeps the others where they are.
     */
    public FloatingItem fan(Entity holder, int slot, int slots, float reach, float height) {
        entityData.set(HOLDER, holder.getId());
        entityData.set(POSE, (byte) Pose.FAN.ordinal());
        entityData.set(TIP, (byte) Tip.UP.ordinal());
        setSlots(slot, slots);
        entityData.set(RADIUS, reach);
        entityData.set(HEIGHT, height);
        setDeltaMovement(Vec3.ZERO);
        return this;
    }

    public void setSlots(int slot, int slots) {
        entityData.set(SLOT, (byte) slot);
        entityData.set(SLOTS, (byte) Math.max(1, slots));
    }

    /**
     * Flies along {@code velocity} (blocks a tick) for {@code range} blocks. With {@code hitsEntities} it stops at the first living
     * entity of {@code relation} to {@code owner}. It always stops at blocks. Then {@code onHit} runs (null for none) and it sticks there
     * for {@code stickTicks}.
     */
    public FloatingItem fly(@Nullable LivingEntity owner, Vec3 velocity, double range, boolean hitsEntities, Targets.Relation relation,
                           @Nullable HitHandler onHit, int stickTicks) {
        this.owner = owner;
        this.rangeLeft = range;
        this.hitsEntities = hitsEntities;
        this.relation = relation;
        this.onHit = onHit;
        this.stickTicks = stickTicks;
        entityData.set(STUCK_IN, -1);
        entityData.set(HOLDER, -1);
        entityData.set(POSE, (byte) Pose.FREE.ordinal());
        entityData.set(TIP, (byte) Tip.ALONG.ordinal());
        face(velocity);
        setDeltaMovement(velocity);
        hasImpulse = true;
        return this;
    }

    /**
     * A flying object that hits an entity stays stuck in it, moving with it for its stick time, instead of hanging
     * where the entity was. Call it before or after fly(...).
     */
    public FloatingItem sticksToTarget() {
        this.sticksToTarget = true;
        return this;
    }

    /**
     * the entity it is stuck in, or null
     */
    @Nullable
    public Entity stuckIn() {
        int id = entityData.get(STUCK_IN);
        return id < 0 ? null : level().getEntity(id);
    }

    /**
     * Stays put pointing {@code tip} for {@code ticks}, then fades.
     */
    public FloatingItem stay(Vec3 at, Vec3 tip, int ticks) {
        entityData.set(STUCK_IN, -1);
        entityData.set(HOLDER, -1);
        entityData.set(POSE, (byte) Pose.FREE.ordinal());
        entityData.set(TIP, (byte) Tip.ALONG.ordinal());
        setPos(at);
        face(tip);
        setDeltaMovement(Vec3.ZERO);
        hasImpulse = true;
        life = ticks;
        return this;
    }

    /**
     * fades after {@code ticks} whatever it is doing. -1 lasts until it is taken away
     */
    public FloatingItem life(int ticks) {
        life = ticks;
        return this;
    }

    /**
     * a dart at {@code target} and back, drawn on the client
     */
    public void strike(Entity target) {
        entityData.set(STRIKE_TARGET, target.getId());
        entityData.set(STRIKE_AT, (int) level().getGameTime());
    }

    private void face(Vec3 direction) {
        if (direction.lengthSqr() < 1.0E-6) return;
        Vec3 d = direction.normalize();
        setYRot((float) (Mth.atan2(d.x, d.z) * Mth.RAD_TO_DEG));
        setXRot((float) (Math.asin(Mth.clamp(d.y, -1, 1)) * Mth.RAD_TO_DEG));
        yRotO = getYRot();
        xRotO = getXRot();
    }

    // --- where it is ---

    /**
     * the angle round its circle at {@code time} (game time plus partial tick)
     */
    public double orbitAngle(double time) {
        return entityData.get(SLOT) * Mth.TWO_PI / entityData.get(SLOTS) + time * entityData.get(SPEED);
    }

    /**
     * how far a FAN object leans from straight up at {@code time}, in radians, toward its holder's right when positive. It
     * sways a little
     */
    public double fanAngle(double time) {
        int slot = entityData.get(SLOT), slots = entityData.get(SLOTS);
        double step = slots < 2 ? 0 : Math.min(FAN_SPREAD, FAN_STEP * (slots - 1)) / (slots - 1);
        // slot 0, 1, 2, 3... sits 0, +1, -1, +2... steps from the middle, or +0.5, -0.5, +1.5... for an even fan
        double out = slots % 2 == 1 ? (slot + 1) / 2 : slot / 2 + 0.5;
        double side = slots % 2 == 1 ? (slot % 2 == 1 ? 1 : -1) : (slot % 2 == 0 ? 1 : -1);
        return side * out * step + Math.sin(time * 0.06 + slot * 1.3) * 0.03;
    }

    /**
     * a FAN object's tip direction, for a holder facing {@code holderYaw} degrees
     */
    public Vec3 fanTip(double time, float holderYaw) {
        double yaw = holderYaw * Mth.DEG_TO_RAD, lean = fanAngle(time);
        Vec3 back = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return right.scale(Math.sin(lean)).add(0, Math.cos(lean) * Math.cos(FAN_TILT), 0).add(back.scale(Math.cos(lean) * Math.sin(FAN_TILT)));
    }

    private static final double FAN_SPREAD = Math.toRadians(150), FAN_STEP = Math.toRadians(28);
    /**
     * how far the fan leans back from upright
     */
    public static final float FAN_TILT = (float) Math.toRadians(20);

    /**
     * Where an ORBIT, ROW or FAN object is at {@code time}, the holder at {@code holderPos}. FREE objects are where they are.
     */
    public Vec3 posedAt(double time, @Nullable Entity holder, Vec3 holderPos, float holderYaw) {
        Vector3f anchor = entityData.get(ANCHOR);
        Vec3 center = holder != null ? holderPos : new Vec3(anchor.x, anchor.y, anchor.z);
        float radius = entityData.get(RADIUS), height = entityData.get(HEIGHT);
        int slot = entityData.get(SLOT), slots = entityData.get(SLOTS);
        return switch (getFloatPose()) {
            case ORBIT -> {
                double angle = orbitAngle(time);
                double bob = getTip() == Tip.UP ? Math.sin(time * 0.08 + slot * 1.7) * 0.08 : 0;
                yield center.add(Math.cos(angle) * radius, height + bob, Math.sin(angle) * radius);
            }
            case ROW -> {
                double yaw = holderYaw * Mth.DEG_TO_RAD;
                Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
                double across = (slot - (slots - 1) / 2.0) * 0.5;
                yield center.add(forward.scale(radius)).add(right.scale(across)).add(0, height, 0);
            }
            case FAN -> {
                double yaw = holderYaw * Mth.DEG_TO_RAD;
                Vec3 back = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
                double bob = Math.sin(time * 0.08 + slot * 1.7) * 0.04;
                yield center.add(back.scale(0.35)).add(0, height + bob, 0).add(fanTip(time, holderYaw).scale(radius));
            }
            case FREE -> position();
        };
    }

    /**
     * how far through its strike dart it is at {@code time}, 0 to 1, or -1 when it isn't darting
     */
    public double strikeProgress(double time) {
        if (entityData.get(STRIKE_TARGET) < 0) return -1;
        double progress = (time - entityData.get(STRIKE_AT)) / STRIKE_TICKS;
        return progress < 0 || progress >= 1 ? -1 : progress;
    }

    @Nullable
    public Entity strikeTarget() {
        int id = entityData.get(STRIKE_TARGET);
        return id < 0 ? null : level().getEntity(id);
    }

    @Override
    public void tick() {
        super.tick();
        Pose pose = getFloatPose();
        if (pose != Pose.FREE) {
            Entity holder = getHolder();
            if (entityData.get(HOLDER) >= 0 && (holder == null || !holder.isAlive())) {
                if (!level().isClientSide()) discard();
                return;
            }
            setPos(posedAt(level().getGameTime(), holder, holder == null ? Vec3.ZERO : holder.position(),
                    holder instanceof LivingEntity living ? living.yBodyRot : holder == null ? 0 : holder.getYRot()));
        } else if (entityData.get(STUCK_IN) >= 0) {
            Entity in = stuckIn();
            if (in != null && in.isAlive()) {
                Vector3f at = entityData.get(STUCK_AT);
                setPos(in.getX() + at.x, in.getY() + at.y, in.getZ() + at.z);
            } else if (!level().isClientSide()) {
                // what it was stuck in is gone: it hangs where it is until its time is up
                entityData.set(STUCK_IN, -1);
            }
        } else if (getDeltaMovement().lengthSqr() > 0) {
            if (level().isClientSide()) setPos(position().add(getDeltaMovement()));
            else flyTick();
        }
        if (!level().isClientSide() && life >= 0 && life-- == 0) discard();
    }

    private void flyTick() {
        Vec3 from = position();
        Vec3 velocity = getDeltaMovement();
        double step = Math.min(velocity.length(), rangeLeft);
        Vec3 to = from.add(velocity.normalize().scale(step));
        BlockHitResult block = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (block.getType() != HitResult.Type.MISS) to = block.getLocation();
        if (hitsEntities) {
            EntityHitResult entity = ProjectileUtil.getEntityHitResult(level(), this, from, to, getBoundingBox().expandTowards(to.subtract(from)).inflate(0.6),
                    e -> e instanceof LivingEntity living && Targets.targetable(living) && e != owner
                            && (owner == null || Targets.matches(owner, e, relation)));
            if (entity != null) {
                LivingEntity target = (LivingEntity) entity.getEntity();
                Vec3 at = target.getBoundingBox().getCenter();
                land(target, from.add(at.subtract(from).normalize().scale(Math.max(0, from.distanceTo(at) - target.getBbWidth() / 2))));
                return;
            }
        }
        rangeLeft -= from.distanceTo(to);
        setPos(to);
        if (block.getType() != HitResult.Type.MISS || rangeLeft <= 1.0E-3) land(null, to);
    }

    private void land(@Nullable LivingEntity target, Vec3 at) {
        Vec3 direction = getDeltaMovement();
        stay(at, direction, stickTicks);
        if (target != null && sticksToTarget) {
            entityData.set(STUCK_AT, at.subtract(target.position()).toVector3f());
            entityData.set(STUCK_IN, target.getId());
        }
        HitHandler handler = onHit;
        onHit = null;
        if (handler != null) handler.hit(target, at);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
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
