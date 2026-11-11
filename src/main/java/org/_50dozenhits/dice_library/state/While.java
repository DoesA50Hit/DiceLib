package org._50dozenhits.dice_library.state;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Modifiers and effects that come and go with a condition, such as sprinting. Call it every tick,
 * or whenever the condition may have changed, with the condition as {@code on}. It only touches the entity when something
 * changes. Call it with false once the condition stops mattering too (the ability is lost, a toggle goes off), or
 * the bonus stays.
 */
public final class While {
    /**
     * Puts the modifier on while {@code on}, takes it off otherwise.
     */
    public static void modifier(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id, double amount,
                                AttributeModifier.Operation operation, boolean on) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) return;
        if (on) {
            if (!instance.hasModifier(id)) instance.addTransientModifier(new AttributeModifier(id, amount, operation));
        } else if (instance.hasModifier(id)) {
            instance.removeModifier(id);
        }
    }

    /**
     * Keeps a modifier at {@code amount}, replacing it when the amount changed. 0 takes it off. For bonuses that grow with
     * something (attack speed per combo hit).
     */
    public static void scaledModifier(LivingEntity entity, Holder<Attribute> attribute, ResourceLocation id, double amount,
                                      AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier current = instance.getModifier(id);
        if (current != null && current.amount() == amount) return;
        if (current != null) instance.removeModifier(id);
        if (amount != 0) instance.addTransientModifier(new AttributeModifier(id, amount, operation));
    }

    /**
     * Keeps an effect up while {@code on}. Given as ambient, without particles, so it can be told apart from the same effect
     * from a potion and taken off again without touching that one.
     */
    public static void effect(LivingEntity entity, Holder<MobEffect> effect, int amplifier, boolean on) {
        MobEffectInstance current = entity.getEffect(effect);
        boolean ours = current != null && current.isAmbient() && !current.isVisible() && current.getAmplifier() == amplifier;
        if (on) {
            if (current == null || ours && current.getDuration() < 220) {
                entity.addEffect(new MobEffectInstance(effect, 300, amplifier, true, false, true));
            }
        } else if (ours) {
            entity.removeEffect(effect);
        }
    }
}
