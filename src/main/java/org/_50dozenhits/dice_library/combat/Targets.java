package org._50dozenhits.dice_library.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.DiceLib;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * What a caster looks at, and who stands around it. "Ally" is isAlly: vanilla's isAlliedTo (same team, or a tamed
 * animal and its owner) checked both ways, the same again for whoever owns each of the two, and any rule a mod added
 * with allyRule. A mob held with Control counts as its controller's. Every search skips what isn't targetable: the dead, spectators and the dicelib:untargetable tag
 * (armour stands and entity copies).
 */
public final class Targets {
    /**
     * entity types no search finds: scenery that happens to be a living entity
     */
    public static final TagKey<EntityType<?>> UNTARGETABLE = TagKey.create(Registries.ENTITY_TYPE, DiceLib.id("untargetable"));

    private static final List<BiPredicate<Entity, Entity>> ALLY_RULES = new CopyOnWriteArrayList<>();

    /**
     * Whether an ability can pick this entity at all: alive, not a spectator, and not in UNTARGETABLE. Every search
     * here checks it. Check it yourself for entities found another way.
     */
    public static boolean targetable(LivingEntity entity) {
        return entity.isAlive() && !entity.isSpectator() && !entity.getType().is(UNTARGETABLE);
    }

    /**
     * Adds a way for two entities to be allies, on top of vanilla's, such as a party. It is asked both
     * ways round, with each entity's owner when it has one (a summon, a projectile). Register once at startup:
     *
     * <pre>{@code
     * Targets.allyRule((a, b) -> a instanceof Player x && b instanceof Player y && Parties.same(x, y));
     * }</pre>
     */
    public static void allyRule(BiPredicate<Entity, Entity> rule) {
        ALLY_RULES.add(rule);
    }

    public enum Relation {
        ALLIES,
        NON_ALLIES,
        ANY
    }

    /**
     * The nearest entity on the eye line, else the block that stops the line, else a miss. Blocks hide what's behind
     * them. Spectators and the caster are skipped.
     */
    public static HitResult eyeLine(LivingEntity caster, double range) {
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult block = caster.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        Vec3 reach = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        AABB area = caster.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(caster, eye, reach, area,
                e -> !e.isSpectator() && e.isPickable(), eye.distanceToSqr(reach));
        return entity != null ? entity : block;
    }

    @Nullable
    public static LivingEntity lookedAt(LivingEntity caster, double range, Relation relation) {
        HitResult hit = eyeLine(caster, range);
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity target
                && targetable(target) && matches(caster, target, relation)) {
            return target;
        }
        return null;
    }

    /**
     * never includes the origin itself
     */
    public static List<LivingEntity> inBox(LivingEntity origin, AABB box, Relation relation) {
        return origin.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != origin && targetable(e) && matches(origin, e, relation));
    }

    public static List<LivingEntity> around(LivingEntity origin, double radius, Relation relation) {
        double radiusSqr = radius * radius;
        List<LivingEntity> found = inBox(origin, origin.getBoundingBox().inflate(radius), relation);
        found.removeIf(e -> e.distanceToSqr(origin) > radiusSqr);
        return found;
    }

    /**
     * Everyone whose feet are within {@code radius} of a point, {@code relation} seen from {@code origin}. Never the origin itself.
     */
    public static List<LivingEntity> around(LivingEntity origin, Vec3 center, double radius, Relation relation) {
        return around(origin.level(), center, radius, e -> e != origin && matches(origin, e, relation));
    }

    /**
     * Every living entity whose feet are within {@code radius} of a point and that {@code which} lets through. What isn't
     * targetable is skipped.
     */
    public static List<LivingEntity> around(Level level, Vec3 center, double radius, Predicate<LivingEntity> which) {
        double radiusSqr = radius * radius;
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
                e -> targetable(e) && e.position().distanceToSqr(center) <= radiusSqr && which.test(e));
    }

    /**
     * Everyone whose hitbox comes within {@code radius} of a point, {@code relation} seen from {@code origin}. Never the origin itself.
     * Where around goes by the feet, this catches a big mob whose side is in reach though its middle is not.
     */
    public static List<LivingEntity> touching(LivingEntity origin, Vec3 center, double radius, Relation relation) {
        return touching(origin.level(), center, radius, e -> e != origin && matches(origin, e, relation));
    }

    /**
     * Every living entity whose hitbox comes within {@code radius} of a point and that {@code which} lets through.
     */
    public static List<LivingEntity> touching(Level level, Vec3 center, double radius, Predicate<LivingEntity> which) {
        double radiusSqr = radius * radius;
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
                e -> targetable(e) && e.getBoundingBox().distanceToSqr(center) <= radiusSqr && which.test(e));
    }

    /**
     * Everyone standing in an upright cylinder: {@code radius} around {@code base} along the ground, from {@code base} up {@code height}
     * blocks. Never the origin.
     */
    public static List<LivingEntity> cylinder(LivingEntity origin, Vec3 base, double radius, double height, Relation relation) {
        double radiusSqr = radius * radius;
        AABB box = new AABB(base.x - radius, base.y, base.z - radius, base.x + radius, base.y + height, base.z + radius);
        return origin.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != origin && targetable(e)
                && horizontalDistanceSqr(e.position(), base) <= radiusSqr && matches(origin, e, relation));
    }

    /**
     * Projectiles in flight within {@code radius} of a point, judged by who shot them: NON_ALLIES are the ones fired by
     * anyone not allied with {@code origin}, and ones with no shooter. For abilities that catch, stop or turn arrows.
     */
    public static List<Projectile> projectilesAround(LivingEntity origin, Vec3 center, double radius, Relation relation) {
        double radiusSqr = radius * radius;
        return origin.level().getEntitiesOfClass(Projectile.class, new AABB(center, center).inflate(radius), p -> {
            if (p.isRemoved() || p.position().distanceToSqr(center) > radiusSqr) return false;
            Entity shooter = p.getOwner();
            return switch (relation) {
                case ALLIES -> shooter != null && isAlly(origin, shooter);
                case NON_ALLIES -> shooter == null || !isAlly(origin, shooter);
                case ANY -> true;
            };
        });
    }

    /**
     * a copy, nearest {@code from} first (by the feet)
     */
    public static List<LivingEntity> byDistance(Collection<? extends LivingEntity> entities, Vec3 from) {
        List<LivingEntity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(from)));
        return sorted;
    }

    @Nullable
    public static LivingEntity nearest(Collection<? extends LivingEntity> entities, Vec3 from) {
        LivingEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (LivingEntity e : entities) {
            double distance = e.position().distanceToSqr(from);
            if (distance < bestSqr) {
                bestSqr = distance;
                best = e;
            }
        }
        return best;
    }

    /**
     * the {@code count} nearest, nearest first
     */
    public static List<LivingEntity> nearest(Collection<? extends LivingEntity> entities, Vec3 from, int count) {
        List<LivingEntity> sorted = byDistance(entities, from);
        return sorted.size() <= count ? sorted : new ArrayList<>(sorted.subList(0, Math.max(0, count)));
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /**
     * Everyone the eye line passes within {@code width} blocks of, nearest first, up to the first block in the way. For
     * abilities that pierce.
     */
    public static List<LivingEntity> line(LivingEntity caster, double range, double width, Relation relation) {
        Vec3 eye = caster.getEyePosition();
        Vec3 end = eye.add(caster.getViewVector(1.0F).scale(range));
        BlockHitResult block = caster.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        Vec3 reach = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        List<LivingEntity> found = inBox(caster, new AABB(eye, reach).inflate(width + 1), relation);
        found.removeIf(e -> {
            AABB box = e.getBoundingBox().inflate(width);
            return !box.contains(eye) && box.clip(eye, reach).isEmpty();
        });
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(eye)));
        return found;
    }

    /**
     * Everyone within {@code width} blocks of the segment from {@code from} to {@code to}, nearest {@code from} first. Blocks are up to the
     * caller: stop {@code to} at the first one (blockStop) when they should.
     */
    public static List<LivingEntity> segment(LivingEntity origin, Vec3 from, Vec3 to, double width, Relation relation) {
        List<LivingEntity> found = inBox(origin, new AABB(from, to).inflate(width + 1), relation);
        found.removeIf(e -> {
            AABB box = e.getBoundingBox().inflate(width);
            return !box.contains(from) && box.clip(from, to).isEmpty();
        });
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(from)));
        return found;
    }

    /**
     * where the segment meets its first block, or {@code to} when nothing is in the way
     */
    public static Vec3 blockStop(Entity origin, Vec3 from, Vec3 to) {
        BlockHitResult block = origin.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, origin));
        return block.getType() == HitResult.Type.MISS ? to : block.getLocation();
    }

    /**
     * Everyone within {@code range} the caster can see whose middle is within angle / 2 degrees of where it looks, so
     * {@code angle} is the cone's full width.
     */
    public static List<LivingEntity> cone(LivingEntity caster, double range, double angle, Relation relation) {
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getViewVector(1.0F);
        double minCos = Math.cos(Math.toRadians(angle / 2));
        List<LivingEntity> found = around(caster, range, relation);
        found.removeIf(e -> {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double length = to.length();
            return length > 1.0E-4 && to.dot(look) / length < minCos || !caster.hasLineOfSight(e);
        });
        return found;
    }

    /**
     * Starts at {@code first} and hops to the nearest one not hit yet within {@code hop} blocks of the last, {@code count} in all, first
     * included. The relation is seen from the caster, who is never in the chain.
     */
    public static List<LivingEntity> chain(LivingEntity caster, LivingEntity first, int count, double hop, Relation relation) {
        return chain(caster, first, count, hop, relation, e -> true);
    }

    /**
     * The same, hopping only to those {@code also} lets through (only metal-wearers, only the marked). {@code first} is taken as
     * it is.
     */
    public static List<LivingEntity> chain(LivingEntity caster, LivingEntity first, int count, double hop, Relation relation,
                                           Predicate<LivingEntity> also) {
        List<LivingEntity> chain = new ArrayList<>();
        chain.add(first);
        double hopSqr = hop * hop;
        while (chain.size() < count) {
            LivingEntity last = chain.getLast();
            LivingEntity next = null;
            double best = hopSqr;
            for (LivingEntity e : last.level().getEntitiesOfClass(LivingEntity.class, last.getBoundingBox().inflate(hop),
                    e -> e != caster && targetable(e) && !chain.contains(e) && matches(caster, e, relation) && also.test(e))) {
                double distance = e.distanceToSqr(last);
                if (distance <= best) {
                    best = distance;
                    next = e;
                }
            }
            if (next == null) break;
            chain.add(next);
        }
        return chain;
    }

    /**
     * Whether the two are on the same side: the same entity, vanilla allies either way round, owned by the same
     * entity or by vanilla allies (two summons of one caster, a summon and its caster's team-mate), or allies by a
     * rule added with allyRule.
     */
    public static boolean isAlly(Entity a, Entity b) {
        if (a == b || a.isAlliedTo(b) || b.isAlliedTo(a)) return true;
        Entity ownerA = owner(a), ownerB = owner(b);
        if ((ownerA != a || ownerB != b) && (ownerA == ownerB || ownerA.isAlliedTo(ownerB) || ownerB.isAlliedTo(ownerA))) return true;
        for (BiPredicate<Entity, Entity> rule : ALLY_RULES) {
            if (rule.test(ownerA, ownerB) || rule.test(ownerB, ownerA)) return true;
        }
        return false;
    }

    // The entity at the top of the chain of owners (a projectile's shooter, a summon's caster), or the entity itself.
    private static Entity owner(Entity entity) {
        for (int step = 0; step < 4; step++) {
            Entity owner = entity instanceof TraceableEntity traceable ? traceable.getOwner()
                    : entity instanceof OwnableEntity ownable ? ownable.getOwner() : null;
            // a mob under Control is its controller's for as long as that lasts
            LivingEntity controller = Control.owner(entity);
            if (controller != null) owner = controller;
            if (owner == null || owner == entity) break;
            entity = owner;
        }
        return entity;
    }

    public static boolean matches(Entity origin, Entity other, Relation relation) {
        return switch (relation) {
            case ALLIES -> isAlly(origin, other);
            case NON_ALLIES -> !isAlly(origin, other);
            case ANY -> true;
        };
    }
}
