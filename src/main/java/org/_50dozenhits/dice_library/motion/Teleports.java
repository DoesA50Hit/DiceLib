package org._50dozenhits.dice_library.motion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.combat.Targets;
import org._50dozenhits.dice_library.visual.BodyPoints;
import org._50dozenhits.dice_library.visual.Fx;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Teleports for abilities. Every one goes through MoveTeleport, so whatever listens to ender pearls and chorus fruit
 * hears about these too. A teleport goes exactly where it is sent, or asks for a safe spot: one the entity fits in,
 * with ground under it and nothing harmful (lava, fire, the dicelib:unsafe_to_land tag) in or under it. A rider gets
 * off its mount first. Server side.
 *
 * <pre>{@code
 * Teleports.to(caster, point).safe(4, 3).go();                  // the nearest safe spot within 4 along x and z, 3 up or down
 * Teleports.to(caster, point).safe().orAnyway().go();           // safe if it can be, exactly there if not
 * Teleports.blink(caster, 8).throughBlocks().trail(fx("streak"), fallback).go();
 * }</pre>
 */
public final class Teleports {
    /**
     * blocks a safe teleport never lands in or on, on top of every fluid
     */
    public static final TagKey<Block> UNSAFE_TO_LAND = TagKey.create(Registries.BLOCK, DiceLib.id("unsafe_to_land"));

    /**
     * what safe() searches when it is given no numbers: blocks along x and z, and up or down
     */
    public static final int SAFE_SPREAD = 3;
    public static final int SAFE_REACH = 4;

    // ---- the calls ----

    /**
     * A teleport to {@code target} to set up and then go().
     */
    public static Teleport to(Entity entity, Vec3 target) {
        return new Teleport(entity, target);
    }

    /**
     * A blink up to {@code distance} blocks the way the entity looks: stopped by the first wall unless throughBlocks(), and
     * landing at the farthest safe spot along the way unless anywhere().
     */
    public static Blink blink(Entity entity, double distance) {
        return new Blink(entity, distance);
    }

    /**
     * A teleport {@code distance} blocks behind {@code target}'s back, turned to face it, at a safe spot within 1 block of there.
     */
    public static Teleport behind(Entity entity, LivingEntity target, double distance) {
        Vec3 spot = BodyPoints.local(target, -distance, 0, 0);
        return new Teleport(entity, spot).safe(1, 2).facingToward(target.getEyePosition());
    }

    /**
     * A teleport onto whatever the entity looks at within {@code range}: the top of the block, the floor in front of a wall,
     * the entity's feet, or the end of the range when it looks at nothing. Safe within 1 block of there.
     */
    public static Teleport toLookedAt(LivingEntity entity, double range) {
        HitResult hit = Targets.eyeLine(entity, range);
        Vec3 spot = hit.getLocation();
        if (hit instanceof BlockHitResult block && hit.getType() != HitResult.Type.MISS) {
            Direction face = block.getDirection();
            if (face != Direction.UP) spot = spot.add(Vec3.atLowerCornerOf(face.getNormal()).scale(entity.getBbWidth() / 2 + 0.1));
        } else if (hit instanceof EntityHitResult entityHit) {
            spot = entityHit.getEntity().position();
        }
        return new Teleport(entity, spot).safe(1, SAFE_REACH);
    }

    /**
     * Moves the entity to {@code target}, or wherever a listener changed it to, with no safety checks. Empty when a listener
     * cancelled it. The same as to(entity, target).go().
     */
    public static Optional<Vec3> teleport(Entity entity, Vec3 target) {
        return to(entity, target).go();
    }

    /**
     * Swaps where two entities stand, facing as they did. Both teleports are asked first. When a listener cancels
     * either, neither moves and this returns false.
     */
    public static boolean swap(Entity a, Entity b) {
        if (a.level() != b.level()) return false;
        MoveTeleport toB = NeoForge.EVENT_BUS.post(new MoveTeleport(a, b.position()));
        MoveTeleport toA = NeoForge.EVENT_BUS.post(new MoveTeleport(b, a.position()));
        if (toB.isCanceled() || toA.isCanceled()) return false;
        place(a, toB.getTarget());
        place(b, toA.getTarget());
        return true;
    }

    /**
     * Where the player would respawn (bed, a charged anchor or world spawn), in any dimension, without using up an
     * anchor charge. Not an event: nothing should move it.
     */
    public static void toSpawn(ServerPlayer player) {
        DimensionTransition spawn = player.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING);
        player.teleportTo(spawn.newLevel(), spawn.pos().x, spawn.pos().y, spawn.pos().z, spawn.yRot(), spawn.xRot());
        player.resetFallDistance();
    }

    // ---- safe spots ----

    /**
     * Whether {@code entity} could stand with its feet at {@code feet}: it fits there, has ground under it, and nothing it would
     * touch is a fluid, outside the world, or in UNSAFE_TO_LAND.
     */
    public static boolean isSafe(Entity entity, Level level, Vec3 feet) {
        if (feet.y < level.getMinBuildHeight() || feet.y > level.getMaxBuildHeight()) return false;
        AABB box = entity.getDimensions(entity.getPose()).makeBoundingBox(feet);
        if (!level.noCollision(entity, box) || level.containsAnyLiquid(box)) return false;
        AABB underfoot = new AABB(box.minX, feet.y - 0.1, box.minZ, box.maxX, feet.y, box.maxZ);
        if (level.noBlockCollision(entity, underfoot)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(feet.y - 0.1), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            if (level.getBlockState(pos).is(UNSAFE_TO_LAND)) return false;
        }
        return true;
    }

    /**
     * The nearest safe spot (isSafe) to {@code near}, looking up to {@code spread} blocks away along x and z and {@code reach} blocks
     * up or down. {@code near} itself is tried first, then whole blocks outward, the nearest first. A spread of 0 only
     * looks straight up and down, a reach of 0 only at the same height.
     */
    public static Optional<Vec3> findSafe(Entity entity, Level level, Vec3 near, int spread, int reach) {
        BlockPos base = BlockPos.containing(near);
        for (int[] offset : offsets(Math.max(0, spread), Math.max(0, reach))) {
            BlockPos pos = base.offset(offset[0], offset[1], offset[2]);
            double floor = level.getBlockFloorHeight(pos);
            if (!DismountHelper.isBlockFloorValid(floor)) continue;
            double x = offset[0] == 0 && offset[2] == 0 ? near.x : pos.getX() + 0.5;
            double z = offset[0] == 0 && offset[2] == 0 ? near.z : pos.getZ() + 0.5;
            Vec3 feet = new Vec3(x, pos.getY() + floor, z);
            if (Math.abs(feet.y - near.y) <= reach + 1 && isSafe(entity, level, feet)) return Optional.of(feet);
        }
        return Optional.empty();
    }

    /**
     * A safe spot for {@code entity} at a random point {@code min} to {@code max} blocks from {@code center} along the ground, landing up to
     * SAFE_REACH blocks up or down. Tries several directions. Empty when none had one.
     */
    public static Optional<Vec3> randomSafeSpot(Entity entity, Vec3 center, double min, double max) {
        RandomSource random = entity.getRandom();
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * Math.max(0, max - min);
            Optional<Vec3> spot = findSafe(entity, entity.level(), center.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance), 0, SAFE_REACH);
            if (spot.isPresent()) return spot;
        }
        return Optional.empty();
    }

    /**
     * A spot near {@code target} (within 3 blocks up or down, same x and z) with solid ground and two blocks of room, or
     * null. The nearest height is tried first. For code with no entity at hand. findSafe fits the entity's own size.
     */
    @Nullable
    public static Vec3 standableNear(Level level, Vec3 target) {
        BlockPos base = BlockPos.containing(target);
        for (int dy = 0; dy <= 6; dy++) {
            int offset = (dy % 2 == 0 ? 1 : -1) * ((dy + 1) / 2);
            BlockPos feet = base.above(offset);
            if (level.getBlockState(feet.below()).isSolid() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return new Vec3(target.x, feet.getY(), target.z);
            }
        }
        return null;
    }

    // Every offset within the box, nearest first. On a tie, lower first, so a spot falls to the ground before it climbs.
    private static List<int[]> offsets(int spread, int reach) {
        List<int[]> list = new ArrayList<>();
        for (int dx = -spread; dx <= spread; dx++) {
            for (int dz = -spread; dz <= spread; dz++) {
                for (int dy = -reach; dy <= reach; dy++) list.add(new int[]{dx, dy, dz});
            }
        }
        list.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]).thenComparingInt(o -> o[1]));
        return list;
    }

    private static void place(Entity entity, Vec3 to) {
        // a rider gets off first, as with an ender pearl. Left mounted it would be carried straight back
        if (entity.isPassenger()) entity.stopRiding();
        entity.teleportTo(to.x, to.y, to.z);
        entity.resetFallDistance();
    }

    // ---- the builders ----

    /**
     * What a teleport and a blink share: safety, facing, motion, and what shows at each end.
     */
    public abstract static class Travel<T extends Travel<T>> {
        protected final Entity entity;
        protected boolean safe;
        protected int spread = SAFE_SPREAD;
        protected int reach = SAFE_REACH;
        protected boolean anyway;
        @Nullable
        private Float yaw;
        @Nullable
        private Float pitch;
        @Nullable
        private Vec3 faceToward;
        private boolean stop;
        @Nullable
        private ResourceLocation departFx;
        @Nullable
        private ResourceLocation arriveFx;
        @Nullable
        private ResourceLocation trailFx;
        private Fx.Fallback fallback = Fx.Fallback.burst(ParticleTypes.PORTAL, 0.6f, 24);
        private Fx.Fallback trailFallback = Fx.Fallback.line(ParticleTypes.PORTAL);
        @Nullable
        private SoundEvent sound;
        private float volume = 1;
        private float pitchOfSound = 1;

        protected Travel(Entity entity) {
            this.entity = entity;
        }

        @SuppressWarnings("unchecked")
        private T self() {
            return (T) this;
        }

        /**
         * Lands only at a safe spot, the nearest within SAFE_SPREAD blocks along x and z and SAFE_REACH up or down.
         * With none, the teleport is refused (unless orAnyway()).
         */
        public T safe() {
            return safe(SAFE_SPREAD, SAFE_REACH);
        }

        /**
         * the same, searching {@code spread} blocks along x and z and {@code reach} up or down (spread 0: straight up and down)
         */
        public T safe(int spread, int reach) {
            this.safe = true;
            this.spread = spread;
            this.reach = reach;
            return self();
        }

        /**
         * with safe(): when no safe spot is found, go to the exact spot anyway instead of refusing
         */
        public T orAnyway() {
            this.anyway = true;
            return self();
        }

        /**
         * turned to face {@code yaw} and {@code pitch} (degrees) on arrival
         */
        public T facing(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            return self();
        }

        /**
         * turned to look at {@code point} on arrival
         */
        public T facingToward(Vec3 point) {
            this.faceToward = point;
            return self();
        }

        /**
         * stops all motion on arrival, so a falling or dashing entity doesn't carry it through
         */
        public T stop() {
            this.stop = true;
            return self();
        }

        /**
         * effects (Fx ids) where it left and where it arrived (either may be null), and what clients with no effect
         * player for them draw (default: a puff of portal particles)
         */
        public T fx(@Nullable ResourceLocation depart, @Nullable ResourceLocation arrive, Fx.Fallback fallback) {
            this.departFx = depart;
            this.arriveFx = arrive;
            this.fallback = fallback;
            return self();
        }

        /**
         * a line effect from where it left to where it arrived, at chest height
         */
        public T trail(ResourceLocation id, Fx.Fallback fallback) {
            this.trailFx = id;
            this.trailFallback = fallback;
            return self();
        }

        /**
         * played at both ends
         */
        public T sound(SoundEvent sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitchOfSound = pitch;
            return self();
        }

        /**
         * where it would land now, with no event and nothing moved. Empty when it would be refused
         */
        public Optional<Vec3> preview() {
            return entity.level().isClientSide() ? Optional.empty() : landing();
        }

        /**
         * Teleports. Returns where it landed. Empty when no safe spot was found or a MoveTeleport listener cancelled.
         */
        public Optional<Vec3> go() {
            if (entity.level().isClientSide()) return Optional.empty();
            Optional<Vec3> landing = landing();
            if (landing.isEmpty()) return Optional.empty();
            MoveTeleport event = NeoForge.EVENT_BUS.post(new MoveTeleport(entity, landing.get()));
            if (event.isCanceled()) return Optional.empty();
            Vec3 to = event.getTarget();
            ServerLevel from = (ServerLevel) entity.level();
            ServerLevel into = destination();
            Vec3 start = entity.position();
            Vec3 startChest = BodyPoints.middle(entity);
            if (departFx != null) Fx.at(from, departFx, start, entity.getBbWidth() + 0.4f, fallback);
            if (sound != null) Fx.sound(from, start, sound, volume, pitchOfSound);

            float newYaw = entity.getYRot(), newPitch = entity.getXRot();
            if (faceToward != null) {
                Vec3 eye = to.add(0, entity.getEyeHeight(), 0);
                Vec3 d = faceToward.subtract(eye);
                newYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
                newPitch = (float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
            } else if (yaw != null) {
                newYaw = yaw;
                newPitch = pitch;
            }
            if (entity.isPassenger()) entity.stopRiding();
            if (into == from && faceToward == null && yaw == null) {
                entity.teleportTo(to.x, to.y, to.z);
            } else {
                entity.teleportTo(into, to.x, to.y, to.z, Set.of(), newYaw, newPitch);
                if (entity instanceof LivingEntity living) living.setYHeadRot(newYaw);
            }
            entity.resetFallDistance();
            if (stop) {
                entity.setDeltaMovement(Vec3.ZERO);
                entity.hurtMarked = true;
            }
            if (arriveFx != null) Fx.at(into, arriveFx, to, entity.getBbWidth() + 0.4f, fallback);
            if (sound != null) Fx.sound(into, to, sound, volume, pitchOfSound);
            if (trailFx != null && into == from) Fx.line(from, trailFx, startChest, to.add(0, entity.getBbHeight() / 2, 0), trailFallback);
            return Optional.of(to);
        }

        protected ServerLevel destination() {
            return (ServerLevel) entity.level();
        }

        /**
         * where it lands, or empty when refused. Nothing changes
         */
        protected abstract Optional<Vec3> landing();
    }

    /**
     * A teleport to one point: exactly there, or the nearest safe spot (safe), in this level or another (in).
     */
    public static final class Teleport extends Travel<Teleport> {
        private final Vec3 target;
        @Nullable
        private ServerLevel level;

        private Teleport(Entity entity, Vec3 target) {
            super(entity);
            this.target = target;
        }

        /**
         * into another level (a dimension). The safe search runs there. A mob arrives as a new entity in that level.
         */
        public Teleport in(ServerLevel level) {
            this.level = level;
            return this;
        }

        @Override
        protected ServerLevel destination() {
            return level != null ? level : super.destination();
        }

        @Override
        protected Optional<Vec3> landing() {
            if (!safe) return Optional.of(target);
            Optional<Vec3> spot = findSafe(entity, destination(), target, spread, reach);
            return spot.isPresent() || !anyway ? spot : Optional.of(target);
        }
    }

    /**
     * A blink: a short jump along a direction, stopped by walls unless throughBlocks(), landing at the farthest safe
     * spot it can reach. Safe by default, searching straight up and down within SAFE_REACH (safe(spread, reach) to
     * look sideways too).
     */
    public static final class Blink extends Travel<Blink> {
        private final double distance;
        private double least = 1;
        @Nullable
        private Vec3 direction;
        private boolean flat;
        private boolean through;

        private Blink(Entity entity, double distance) {
            super(entity);
            this.distance = distance;
            this.safe = true;
            this.spread = 0;
        }

        /**
         * along the way the entity looks, with the up and down taken out
         */
        public Blink flat() {
            this.flat = true;
            return this;
        }

        /**
         * along any direction instead of the look
         */
        public Blink direction(Vec3 direction) {
            this.direction = direction;
            return this;
        }

        /**
         * walls don't stop it: it can land on the far side of one
         */
        public Blink throughBlocks() {
            this.through = true;
            return this;
        }

        /**
         * lands at the far end exactly, safe or not (through a wall that can be inside one)
         */
        public Blink anywhere() {
            this.safe = false;
            return this;
        }

        /**
         * refused when it can't get at least {@code blocks} away (default 1)
         */
        public Blink atLeast(double blocks) {
            this.least = blocks;
            return this;
        }

        @Override
        protected Optional<Vec3> landing() {
            Vec3 start = entity.position();
            Vec3 dir = direction != null ? direction : entity instanceof LivingEntity living && flat ? BodyPoints.flatLook(living) : entity.getViewVector(1);
            if (flat) dir = new Vec3(dir.x, 0, dir.z);
            if (dir.lengthSqr() < 1.0E-6) return Optional.empty();
            dir = dir.normalize();
            double far = distance;
            if (!through) {
                Vec3 middle = BodyPoints.middle(entity);
                BlockHitResult hit = entity.level().clip(new ClipContext(middle, middle.add(dir.scale(distance)), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, entity));
                if (hit.getType() != HitResult.Type.MISS) far = Math.max(0, middle.distanceTo(hit.getLocation()) - entity.getBbWidth() / 2 - 0.1);
            }
            if (far < least) return Optional.empty();
            if (!safe) return Optional.of(start.add(dir.scale(far)));
            for (double d = far; d >= least; d -= 0.5) {
                Optional<Vec3> spot = findSafe(entity, entity.level(), start.add(dir.scale(d)), spread, reach);
                if (spot.isPresent() && spot.get().distanceTo(start) >= least && (through || inSight(spot.get()))) return spot;
            }
            return anyway ? Optional.of(start.add(dir.scale(far))) : Optional.empty();
        }

        // A blink that walls stop never lands where it couldn't see from its start (down through a floor, say).
        private boolean inSight(Vec3 feet) {
            Vec3 from = BodyPoints.middle(entity);
            Vec3 to = feet.add(0, entity.getBbHeight() / 2, 0);
            return entity.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType()
                    == HitResult.Type.MISS;
        }
    }

    /**
     * A teleport from Teleports. Change the target with setTargetX/Y/Z, or cancel it.
     */
    public static class MoveTeleport extends EntityTeleportEvent implements ICancellableEvent {
        public MoveTeleport(Entity entity, Vec3 target) {
            super(entity, target.x, target.y, target.z);
        }
    }
}
