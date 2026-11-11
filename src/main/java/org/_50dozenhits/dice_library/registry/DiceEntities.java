package org._50dozenhits.dice_library.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.combat.Shot;
import org._50dozenhits.dice_library.prop.AnimatedProp;
import org._50dozenhits.dice_library.prop.EntityCopy;
import org._50dozenhits.dice_library.prop.FloatingItem;
import org._50dozenhits.dice_library.prop.Tether;

/**
 * The props (prop/) and the shot (combat/Shot). None is saved or summonable. Floating items, tethers and shots are
 * sent every tick so flights and orbits stay smooth.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class DiceEntities {
    public static final DeferredRegister<EntityType<?>> REGISTER = DeferredRegister.create(Registries.ENTITY_TYPE, DiceLib.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FloatingItem>> FLOATING_ITEM = REGISTER.register("floating_item",
            () -> EntityType.Builder.<FloatingItem>of(FloatingItem::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(8)
                    .updateInterval(1).noSave().noSummon().fireImmune().build(DiceLib.id("floating_item").toString()));
    public static final DeferredHolder<EntityType<?>, EntityType<Tether>> TETHER = REGISTER.register("tether",
            () -> EntityType.Builder.<Tether>of(Tether::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(8)
                    .updateInterval(1).noSave().noSummon().fireImmune().build(DiceLib.id("tether").toString()));
    public static final DeferredHolder<EntityType<?>, EntityType<AnimatedProp>> ANIMATED_PROP = REGISTER.register("animated_prop",
            () -> EntityType.Builder.<AnimatedProp>of(AnimatedProp::new, MobCategory.MISC).sized(1f, 1f).clientTrackingRange(10)
                    .noSave().noSummon().fireImmune().build(DiceLib.id("animated_prop").toString()));
    public static final DeferredHolder<EntityType<?>, EntityType<EntityCopy>> ENTITY_COPY = REGISTER.register("entity_copy",
            () -> EntityType.Builder.<EntityCopy>of(EntityCopy::new, MobCategory.MISC).sized(0.6f, 1.8f).clientTrackingRange(10)
                    .noSave().noSummon().build(DiceLib.id("entity_copy").toString()));

    public static final DeferredHolder<EntityType<?>, EntityType<Shot>> SHOT = REGISTER.register("shot",
            () -> EntityType.Builder.<Shot>of(Shot::new, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(8)
                    .updateInterval(1).noSave().noSummon().fireImmune().build(DiceLib.id("shot").toString()));

    @SubscribeEvent
    static void attributes(EntityAttributeCreationEvent event) {
        event.put(ENTITY_COPY.get(), LivingEntity.createLivingAttributes().build());
    }
}
