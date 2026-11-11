package org._50dozenhits.dice_library.prop;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org._50dozenhits.dice_library.registry.DiceEntities;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A still copy of a living entity where it stands: a player in their own skin (wide or slim arms), armor and held
 * items, or a mob drawn as a fresh one of its type with its armor and held items (EntityCopyRenderer). Use it as a
 * decoy or a statue. It can't be hurt, doesn't move or think, and is never saved. {@code life} takes it away (with
 * onEnd run first) unless something discards it sooner.
 *
 * <pre>{@code
 * EntityCopy decoy = EntityCopy.copy(player).life(60).onEnd(copy -> Fx.at(level, smoke, copy.position(), 1, fallback));
 * }</pre>
 */
public class EntityCopy extends LivingEntity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(EntityCopy.class, EntityDataSerializers.OPTIONAL_UUID);
    // the entity type copied, drawn with that type's renderer when it isn't a player
    private static final EntityDataAccessor<String> COPIED_TYPE = SynchedEntityData.defineId(EntityCopy.class, EntityDataSerializers.STRING);

    private final NonNullList<ItemStack> hands = NonNullList.withSize(2, ItemStack.EMPTY);
    private final NonNullList<ItemStack> armor = NonNullList.withSize(4, ItemStack.EMPTY);
    // server only
    private int life = -1;
    @Nullable
    private Consumer<EntityCopy> onEnd;

    public EntityCopy(EntityType<? extends EntityCopy> type, Level level) {
        super(type, level);
    }

    /**
     * a copy of {@code of} where it stands, facing the way it faces, holding and wearing what it does
     */
    public static EntityCopy copy(LivingEntity of) {
        EntityCopy decoy = new EntityCopy(DiceEntities.ENTITY_COPY.get(), of.level());
        decoy.entityData.set(COPIED_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(of.getType()).toString());
        decoy.moveTo(of.getX(), of.getY(), of.getZ(), of.getYRot(), 0);
        decoy.yBodyRot = of.yBodyRot;
        decoy.yHeadRot = of.getYHeadRot();
        decoy.entityData.set(OWNER, Optional.of(of.getUUID()));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot.getType() == EquipmentSlot.Type.HAND) {
                decoy.setItemSlot(slot, of.getItemBySlot(slot).copy());
            }
        }
        of.level().addFreshEntity(decoy);
        return decoy;
    }

    /**
     * removed after {@code ticks}, running onEnd first. -1 (the default) lasts until discarded
     */
    public EntityCopy life(int ticks) {
        life = ticks;
        return this;
    }

    public EntityCopy onEnd(Consumer<EntityCopy> onEnd) {
        this.onEnd = onEnd;
        return this;
    }

    public String getCopiedType() {
        return entityData.get(COPIED_TYPE);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || life < 0) return;
        if (life-- == 0) {
            if (onEnd != null) onEnd.accept(this);
            discard();
        }
    }

    /**
     * whose skin it wears (a player copy)
     */
    public Optional<UUID> owner() {
        return entityData.get(OWNER);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
        builder.define(COPIED_TYPE, "minecraft:player");
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return armor;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return switch (slot.getType()) {
            case HAND -> hands.get(slot.getIndex());
            case HUMANOID_ARMOR -> armor.get(slot.getIndex());
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        switch (slot.getType()) {
            case HAND -> hands.set(slot.getIndex(), stack);
            case HUMANOID_ARMOR -> armor.set(slot.getIndex(), stack);
            default -> {
            }
        }
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    // hurt never lands, so /kill and the void take it away directly
    @Override
    public void kill() {
        discard();
    }

    @Override
    protected void onBelowWorld() {
        discard();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
    }
}
