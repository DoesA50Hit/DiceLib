package org._50dozenhits.dice_library.prop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.registry.DiceEntities;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * A GeckoLib model that appears, plays one animation and leaves, such as a fist slamming down or a door opening.
 * It has no AI, can't be hit and is never saved. The model is found by its id ns:name at
 * <pre>{@code
 * assets/ns/geo/prop/name.geo.json, assets/ns/animations/prop/name.animation.json, assets/ns/textures/prop/name.png
 * }</pre>
 * The server runs what you hang on its ticks (on(12, ...)), timed to match the animation, and takes it away at its
 * life.
 *
 * <pre>{@code
 * AnimatedProp.spawn(level, MyMod.id("maw"), at, yaw).animation("animation.maw.strike").life(30)
 *         .on(12, prop -> target.hurt(caster.damageSources().mobAttack(caster), 8));
 * }</pre>
 */
public class AnimatedProp extends Entity implements GeoEntity {
    private static final EntityDataAccessor<String> MODEL = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> ANIMATION = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> LOOPS = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> GLOW = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.BOOLEAN);
    // how far out from its feet the model reaches, for culling: a big model is still drawn when its feet are off screen
    private static final EntityDataAccessor<Float> REACH = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.FLOAT);
    // an entity it stands on and moves with (-1 for none)
    private static final EntityDataAccessor<Integer> FOLLOW = SynchedEntityData.defineId(AnimatedProp.class, EntityDataSerializers.INT);

    @FunctionalInterface
    public interface Cue {
        void run(AnimatedProp prop);
    }

    private record Timed(int tick, Cue cue) {
    }

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    // server only
    private final List<Timed> cues = new ArrayList<>();
    private int life = 40;
    private int age;
    @Nullable
    private String playing;
    @Nullable
    private RawAnimation animation;

    public AnimatedProp(EntityType<? extends AnimatedProp> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /**
     * A new one at {@code at}, facing {@code yaw} degrees (as an entity's), drawn from {@code model}.
     */
    public static AnimatedProp spawn(ServerLevel level, ResourceLocation model, Vec3 at, float yaw) {
        AnimatedProp prop = new AnimatedProp(DiceEntities.ANIMATED_PROP.get(), level);
        prop.entityData.set(MODEL, model.toString());
        prop.moveTo(at.x, at.y, at.z, yaw, 0);
        prop.yRotO = yaw;
        level.addFreshEntity(prop);
        return prop;
    }

    /**
     * the yaw from {@code from} toward {@code to} on the ground, for facing a prop at a target
     */
    public static float yawToward(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return d.horizontalDistanceSqr() < 1.0E-6 ? 0 : (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    /**
     * the animation it plays once from its first tick and then holds on its last frame (a full GeckoLib name)
     */
    public AnimatedProp animation(String name) {
        entityData.set(ANIMATION, name);
        entityData.set(LOOPS, false);
        return this;
    }

    /**
     * the same, looping for as long as it lasts
     */
    public AnimatedProp looping(String name) {
        entityData.set(ANIMATION, name);
        entityData.set(LOOPS, true);
        return this;
    }

    public AnimatedProp life(int ticks) {
        life = ticks;
        return this;
    }

    public AnimatedProp scale(float scale) {
        entityData.set(SCALE, scale);
        return this;
    }

    /**
     * drawn full bright and see-through, for light and fire
     */
    public AnimatedProp glowing() {
        entityData.set(GLOW, true);
        return this;
    }

    /**
     * how far the model reaches from its feet, in blocks (default 3), so it is drawn while any of it is in view
     */
    public AnimatedProp reach(float blocks) {
        entityData.set(REACH, blocks);
        return this;
    }

    /**
     * stands on {@code entity} and moves with it, facing where its body faces
     */
    public AnimatedProp follow(Entity entity) {
        entityData.set(FOLLOW, entity.getId());
        return this;
    }

    /**
     * Runs {@code cue} on the server on that tick of its life (1 is the first). Several can share a tick.
     */
    public AnimatedProp on(int tick, Cue cue) {
        cues.add(new Timed(tick, cue));
        return this;
    }

    public ResourceLocation getModel() {
        ResourceLocation id = ResourceLocation.tryParse(entityData.get(MODEL));
        return id == null ? DiceLib.id("missing") : id;
    }

    public float getScale() {
        return entityData.get(SCALE);
    }

    public boolean isGlowing() {
        return entityData.get(GLOW);
    }

    public int getAge() {
        return age;
    }

    @Nullable
    public Entity getFollowed() {
        int id = entityData.get(FOLLOW);
        return id < 0 ? null : level().getEntity(id);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MODEL, "");
        builder.define(ANIMATION, "");
        builder.define(LOOPS, false);
        builder.define(SCALE, 1f);
        builder.define(GLOW, false);
        builder.define(REACH, 3f);
        builder.define(FOLLOW, -1);
    }

    @Override
    public void tick() {
        super.tick();
        Entity followed = getFollowed();
        if (entityData.get(FOLLOW) >= 0) {
            if (followed == null || !followed.isAlive()) {
                if (!level().isClientSide()) discard();
                return;
            }
            setPos(followed.position());
            float yaw = followed instanceof LivingEntity living ? living.yBodyRot : followed.getYRot();
            yRotO = getYRot();
            setYRot(yaw);
        }
        if (level().isClientSide()) return;
        age++;
        for (Timed timed : List.copyOf(cues)) {
            if (timed.tick() == age) timed.cue().run(this);
        }
        if (age >= life) discard();
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 0, state -> {
            String name = entityData.get(ANIMATION);
            if (name.isEmpty()) return PlayState.STOP;
            if (!name.equals(playing)) {
                playing = name;
                animation = entityData.get(LOOPS) ? RawAnimation.begin().thenLoop(name) : RawAnimation.begin().thenPlayAndHold(name);
            }
            return state.setAndContinue(animation);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        float reach = Math.max(1, entityData.get(REACH) * Mth.abs(getScale()));
        return getBoundingBox().inflate(reach);
    }

    @Override
    public boolean isPickable() {
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
