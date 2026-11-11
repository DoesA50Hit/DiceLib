package org._50dozenhits.dice_library.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.visual.Fx;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A projectile made with Shots.from(...): an entity with no model that the server flies in a line, an arc or after a
 * target, hitting what it passes. What players see is its trail. Its owner is the attacker behind everything it does
 * (Hits, Heals and Targets all look it up), it is never saved, and nothing can hurt it. Server side.
 */
public class Shot extends Entity implements TraceableEntity {
    @Nullable
    private Entity owner;
    private Vec3 velocity = Vec3.ZERO;
    private double rangeLeft = 32;
    private int life = 200;
    private double gravity;
    @Nullable
    private LivingEntity homing;
    private double turn;
    private double width = 0.25;
    private int pierce;
    private Targets.Relation relation = Targets.Relation.NON_ALLIES;
    private boolean throughBlocks;
    private float damage;
    @Nullable
    private ResourceLocation trail;
    @Nullable
    private Fx.Fallback trailFallback;
    private int trailEvery = 1;
    @Nullable
    private Vec3 trailFrom;
    @Nullable
    private BiConsumer<Shot, LivingEntity> onHit;
    @Nullable
    private BiConsumer<Shot, BlockHitResult> onBlock;
    @Nullable
    private BiConsumer<Shot, Shots.End> onEnd;
    @Nullable
    private Consumer<Shot> onTick;
    private final Set<UUID> hit = new HashSet<>();
    private int age;
    private boolean ended;

    public Shot(EntityType<? extends Shot> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    void aim(@Nullable Entity owner, Vec3 from, Vec3 velocity) {
        this.owner = owner;
        this.velocity = velocity;
        setPos(from);
        setDeltaMovement(velocity);
    }

    // ---- setting it up ----

    /**
     * blocks it flies before it ends (32 by default)
     */
    public Shot range(double blocks) {
        this.rangeLeft = Math.max(0, blocks);
        return this;
    }

    /**
     * ticks it lasts at most (200 by default), for one that homes or arcs and may never use up its range
     */
    public Shot life(int ticks) {
        this.life = Math.max(1, ticks);
        return this;
    }

    /**
     * blocks per tick it falls faster each tick, for an arc (0 by default, and an arrow's is 0.05)
     */
    public Shot gravity(double perTick) {
        this.gravity = perTick;
        return this;
    }

    /**
     * turns toward the target's middle by up to {@code degreesPerTick} each tick, keeping its speed. It flies straight
     * on once the target is dead or gone.
     */
    public Shot homing(LivingEntity target, double degreesPerTick) {
        this.homing = target;
        this.turn = Math.toRadians(Math.max(0, degreesPerTick));
        return this;
    }

    /**
     * how near its path must pass an entity's hitbox to hit it, in blocks (0.25 by default)
     */
    public Shot width(double blocks) {
        this.width = Math.max(0, blocks);
        return this;
    }

    /**
     * how many entities it passes through before the one that stops it (0 by default: the first hit ends it)
     */
    public Shot pierce(int count) {
        this.pierce = Math.max(0, count);
        return this;
    }

    /**
     * who it hits, seen from its owner (NON_ALLIES by default). Never the owner.
     */
    public Shot relation(Targets.Relation relation) {
        this.relation = relation;
        return this;
    }

    /**
     * blocks don't stop it
     */
    public Shot throughBlocks() {
        this.throughBlocks = true;
        return this;
    }

    /**
     * hurts each entity it hits for this much, through Hits, so the owner is the attacker
     */
    public Shot damage(float amount) {
        this.damage = amount;
        return this;
    }

    /**
     * A line effect drawn along the stretch it flew, every {@code every} ticks (Fx.line): this is what players see.
     */
    public Shot trail(ResourceLocation id, Fx.Fallback fallback, int every) {
        this.trail = id;
        this.trailFallback = fallback;
        this.trailEvery = Math.max(1, every);
        return this;
    }

    public Shot trail(ResourceLocation id, Fx.Fallback fallback) {
        return trail(id, fallback, 1);
    }

    /**
     * runs for each entity it hits, after any damage
     */
    public Shot onHit(BiConsumer<Shot, LivingEntity> onHit) {
        this.onHit = onHit;
        return this;
    }

    /**
     * runs when a block stops it, with where and which face
     */
    public Shot onBlock(BiConsumer<Shot, BlockHitResult> onBlock) {
        this.onBlock = onBlock;
        return this;
    }

    /**
     * runs every tick it flies, after it has moved, for particles or a sound that a trail can't give
     */
    public Shot onTick(Consumer<Shot> onTick) {
        this.onTick = onTick;
        return this;
    }

    /**
     * runs once when it ends, however it ends, with the shot still at the spot, for an explosion where it lands
     */
    public Shot onEnd(BiConsumer<Shot, Shots.End> onEnd) {
        this.onEnd = onEnd;
        return this;
    }

    /**
     * Adds it to the level and lets it fly.
     */
    public Shot fire() {
        trailFrom = position();
        level().addFreshEntity(this);
        return this;
    }

    // ---- while it flies ----

    @Nullable
    @Override
    public Entity getOwner() {
        return owner;
    }

    /**
     * blocks per tick, as it is now
     */
    public Vec3 velocity() {
        return velocity;
    }

    /**
     * sends it another way mid-flight (a deflect)
     */
    public Shot velocity(Vec3 velocity) {
        this.velocity = velocity;
        return this;
    }

    /**
     * how many entities it has hit so far
     */
    public int hits() {
        return hit.size();
    }

    /**
     * ends it where it is, running onEnd with STOPPED
     */
    public void stop() {
        end(Shots.End.STOPPED);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || ended) return;
        if (++age > life) {
            end(Shots.End.EXPIRED);
            return;
        }
        if (homing != null && homing.isAlive() && homing.level() == level()) steer(homing.getBoundingBox().getCenter());
        if (gravity != 0) velocity = velocity.add(0, -gravity, 0);
        Vec3 from = position();
        double step = Math.min(velocity.length(), rangeLeft);
        if (step < 1.0E-6) {
            end(Shots.End.RANGE);
            return;
        }
        Vec3 to = from.add(velocity.normalize().scale(step));
        BlockHitResult block = null;
        if (!throughBlocks) {
            BlockHitResult clip = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (clip.getType() != HitResult.Type.MISS) {
                block = clip;
                to = clip.getLocation();
            }
        }
        Vec3 end = to;
        List<LivingEntity> found = level().getEntitiesOfClass(LivingEntity.class, new AABB(from, end).inflate(width + 1), entity -> {
            if (entity == owner || hit.contains(entity.getUUID()) || !Targets.targetable(entity) || !Targets.matches(this, entity, relation)) return false;
            AABB box = entity.getBoundingBox().inflate(width);
            return box.contains(from) || box.clip(from, end).isPresent();
        });
        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(from)));
        for (LivingEntity target : found) {
            hit.add(target.getUUID());
            Vec3 at = target.getBoundingBox().inflate(width).clip(from, end).orElse(from);
            setPos(at);
            if (damage > 0) Hits.hit(this, target, damage);
            if (onHit != null) onHit.accept(this, target);
            if (ended || isRemoved()) return;
            if (hit.size() > pierce) {
                trail(at, true);
                end(Shots.End.HIT);
                return;
            }
        }
        setPos(end);
        setDeltaMovement(velocity);
        rangeLeft -= step;
        boolean last = block != null || rangeLeft < 1.0E-6;
        trail(end, last);
        if (onTick != null) onTick.accept(this);
        if (ended) return;
        if (block != null) {
            if (onBlock != null) onBlock.accept(this, block);
            end(Shots.End.BLOCK);
        } else if (last) {
            end(Shots.End.RANGE);
        }
    }

    private void steer(Vec3 toward) {
        double speed = velocity.length();
        Vec3 wanted = toward.subtract(position());
        if (speed < 1.0E-6 || wanted.lengthSqr() < 1.0E-6) return;
        Vec3 now = velocity.scale(1 / speed), aim = wanted.normalize();
        double angle = Math.acos(Math.max(-1, Math.min(1, now.dot(aim))));
        if (angle <= turn || angle < 1.0E-4) {
            velocity = aim.scale(speed);
            return;
        }
        double part = turn / angle, sin = Math.sin(angle);
        // straight back at it: there is no one way round, so nudge it sideways first
        if (sin < 1.0E-4) {
            velocity = now.add(0, 0.01, 0.01).normalize().scale(speed);
            return;
        }
        Vec3 turned = now.scale(Math.sin((1 - part) * angle) / sin).add(aim.scale(Math.sin(part * angle) / sin));
        velocity = turned.normalize().scale(speed);
    }

    private void trail(Vec3 to, boolean last) {
        if (trail == null || trailFallback == null || trailFrom == null || !(level() instanceof ServerLevel level)) return;
        if (!last && age % trailEvery != 0) return;
        if (trailFrom.distanceToSqr(to) > 1.0E-6) Fx.line(level, trail, trailFrom, to, trailFallback);
        trailFrom = to;
    }

    private void end(Shots.End reason) {
        if (ended) return;
        ended = true;
        if (onEnd != null) onEnd.accept(this, reason);
        discard();
    }

    // ---- an entity nothing touches ----

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }
}
