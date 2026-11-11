package org._50dozenhits.dice_library.combat;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.EffectCure;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.visual.Fx;
import org._50dozenhits.dice_library.registry.DiceRegistries;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Healing that remembers where it came from: the entity it came from (a caster, a projectile, a prop, a summon) and,
 * through that, the living healer behind it. Anything that heals someone can go through here, so a "when you heal"
 * rule can listen to HealEvent.Healed and read getSource or getHealer. Vanilla's LivingHealEvent still fires inside, so
 * every other healing rule applies as usual. Server side.
 *
 * <pre>{@code
 * Heals.of(caster, ally, 4).overheal(200).fx(fx("mend")).heal();
 * }</pre>
 *
 * <p>Plus absorption on top of health (absorb), cheating death (survive) and reading how hurt someone is.
 */
public final class Heals {
    /**
     * room for up to 10 more hearts of absorption, so absorb can give any amount. Never shown
     */
    public static final DeferredHolder<MobEffect, MobEffect> EXTRA_ABSORPTION = DiceRegistries.EFFECTS.register("extra_absorption",
            () -> new RoomEffect().addAttributeModifier(Attributes.MAX_ABSORPTION, DiceLib.id("effect.extra_absorption"), 20,
                    AttributeModifier.Operation.ADD_VALUE));

    /**
     * Loads the class, so the effect is registered with the rest. Called once from the mod constructor.
     */
    @ApiStatus.Internal
    public static void init() {
    }

    /**
     * {@code source} is the entity the heal comes from: the caster, or a projectile, prop or summon of theirs. Null for
     * healing from nowhere in particular.
     */
    public static Heal of(@Nullable Entity source, LivingEntity target, float amount) {
        return new Heal(source, target, amount);
    }

    /**
     * {@code fraction} of the target's max health (0.25 = a quarter)
     */
    public static Heal percent(@Nullable Entity source, LivingEntity target, float fraction) {
        return new Heal(source, target, target.getMaxHealth() * fraction);
    }

    /**
     * What was actually healed, after every rule and the target's max health. 0 when cancelled.
     */
    public static float heal(@Nullable Entity source, LivingEntity target, float amount) {
        return of(source, target, amount).heal();
    }

    /**
     * The living entity behind {@code source}: the source itself when it is alive, else its owner (a projectile's shooter, a
     * prop's or summon's owner through TraceableEntity or OwnableEntity). Null when there is none.
     */
    @Nullable
    public static LivingEntity healerOf(@Nullable Entity source) {
        if (source == null) return null;
        if (source instanceof LivingEntity living) return living;
        if (source instanceof TraceableEntity traceable && traceable.getOwner() instanceof LivingEntity owner) return owner;
        if (source instanceof OwnableEntity ownable && ownable.getOwner() instanceof LivingEntity owner) return owner;
        return null;
    }

    /**
     * Absorption on top of what the target has, up to 10 hearts more, for {@code ticks}.
     */
    public static void absorb(LivingEntity target, float amount, int ticks) {
        if (amount <= 0) return;
        MobEffectInstance current = target.getEffect(EXTRA_ABSORPTION);
        int left = current == null ? 0 : current.getDuration();
        target.addEffect(new MobEffectInstance(EXTRA_ABSORPTION, Math.max(ticks, left), 0, false, false, false));
        target.setAbsorptionAmount(Math.min(target.getMaxAbsorption(), target.getAbsorptionAmount() + amount));
    }

    /**
     * The same, with your own status making the room (one with a MAX_ABSORPTION modifier), shown as any
     * status is.
     */
    public static void absorb(LivingEntity target, float amount, int ticks, Holder<MobEffect> room) {
        if (amount <= 0) return;
        MobEffectInstance current = target.getEffect(room);
        int left = current == null ? 0 : current.getDuration();
        target.addEffect(new MobEffectInstance(room, Math.max(ticks, left), 0, false, true, true));
        target.setAbsorptionAmount(Math.min(target.getMaxAbsorption(), target.getAbsorptionAmount() + amount));
    }

    /**
     * Cheats death, or anything else that sets health outright: the target is left at exactly {@code health} (up to its
     * max), with no healing rules and no events. For "the next death leaves you at 1 heart", not for healing.
     */
    public static void survive(LivingEntity target, float health) {
        target.setHealth(health);
    }

    /**
     * health over max health, 0 to 1
     */
    public static float fraction(LivingEntity entity) {
        return entity.getMaxHealth() <= 0 ? 0 : entity.getHealth() / entity.getMaxHealth();
    }

    /**
     * how much health it is missing
     */
    public static float missing(LivingEntity entity) {
        return Math.max(0, entity.getMaxHealth() - entity.getHealth());
    }

    public static final class Heal {
        @Nullable
        private final Entity source;
        private final LivingEntity target;
        private float amount;
        private int overhealTicks;
        @Nullable
        private ResourceLocation fx;
        private float fxScale = -1;
        @Nullable
        private Fx.Fallback fxFallback;
        @Nullable
        private SoundEvent sound;
        private float volume = 1;
        private float pitch = 1;
        @Nullable
        private Consumer<Float> onHealed;

        private Heal(@Nullable Entity source, LivingEntity target, float amount) {
            this.source = source;
            this.target = target;
            this.amount = amount;
        }

        public Heal times(float multiplier) {
            amount *= multiplier;
            return this;
        }

        /**
         * what doesn't fit under max health becomes absorption for {@code ticks} (Heals.absorb)
         */
        public Heal overheal(int ticks) {
            this.overhealTicks = ticks;
            return this;
        }

        /**
         * played on the target when anything was healed, sized to the target unless a scale is given
         */
        public Heal fx(ResourceLocation id) {
            this.fx = id;
            return this;
        }

        public Heal fx(ResourceLocation id, float scale) {
            this.fx = id;
            this.fxScale = scale;
            return this;
        }

        /**
         * The same, with what a client without the effect draws instead of the default burst of hearts. A scale of 0
         * or less sizes it to the target.
         */
        public Heal fx(ResourceLocation id, float scale, Fx.Fallback fallback) {
            this.fx = id;
            this.fxScale = scale;
            this.fxFallback = fallback;
            return this;
        }

        /**
         * played at the target when anything was healed
         */
        public Heal sound(SoundEvent sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
            return this;
        }

        /**
         * runs with what was healed (overheal included) when it was more than 0
         */
        public Heal onHealed(Consumer<Float> onHealed) {
            this.onHealed = onHealed;
            return this;
        }

        /**
         * Heals. Returns what the target gained in health, after every rule and its max health. 0 when cancelled.
         */
        public float heal() {
            if (amount <= 0 || !target.isAlive() || target.level().isClientSide()) return 0;
            HealEvent.Heal event = NeoForge.EVENT_BUS.post(new HealEvent.Heal(target, source, amount));
            if (event.isCanceled() || event.getAmount() <= 0) return 0;
            float before = target.getHealth();
            float over = 0;
            if (overhealTicks > 0) {
                // vanilla's heal, done here so what the healing rules allowed beyond max health is known
                float allowed = EventHooks.onLivingHeal(target, event.getAmount());
                if (allowed > 0 && before > 0) target.setHealth(before + allowed);
                over = Math.max(0, allowed - (target.getHealth() - before));
            } else {
                target.heal(event.getAmount());
            }
            float healed = target.getHealth() - before;
            if (over > 0) absorb(target, over, overhealTicks);
            if (healed > 0) NeoForge.EVENT_BUS.post(new HealEvent.Healed(target, source, healed));
            if (healed + over > 0) {
                if (fx != null) Fx.on(target, fx, fxScale > 0 ? fxScale : target.getBbWidth() + 0.4f,
                        fxFallback != null ? fxFallback : Fx.Fallback.burst(ParticleTypes.HEART, 0.5f, 6));
                if (sound != null) Fx.sound(target, sound, volume, pitch);
                if (onHealed != null) onHealed.accept(healed + over);
            }
            return healed;
        }
    }

    /**
     * getEntity() is the one healed.
     */
    public abstract static class HealEvent extends LivingEvent {
        @Nullable
        private final Entity source;

        protected HealEvent(LivingEntity target, @Nullable Entity source) {
            super(target);
            this.source = source;
        }

        /**
         * the entity the heal came from: a caster, or a projectile, prop or summon of theirs. Null for healing from
         * nowhere in particular
         */
        @Nullable
        public Entity getSource() {
            return source;
        }

        /**
         * the living entity behind the source (Heals.healerOf): the source itself, or its owner. Null for none
         */
        @Nullable
        public LivingEntity getHealer() {
            return healerOf(source);
        }

        /**
         * Before anything is healed. Cancel it, or change how much it heals.
         */
        public static class Heal extends HealEvent implements ICancellableEvent {
            private float amount;

            public Heal(LivingEntity target, @Nullable Entity source, float amount) {
                super(target, source);
                this.amount = amount;
            }

            public float getAmount() {
                return amount;
            }

            public void setAmount(float amount) {
                this.amount = Math.max(0, amount);
            }
        }

        /**
         * After, with what it actually healed. Not posted when nothing was.
         */
        public static class Healed extends HealEvent {
            private final float amount;

            public Healed(LivingEntity target, @Nullable Entity source, float amount) {
                super(target, source);
                this.amount = amount;
            }

            public float getAmount() {
                return amount;
            }
        }
    }

    // The hidden status that makes room for extra absorption. MobEffect's constructor is protected, hence the class.
    private static final class RoomEffect extends MobEffect {
        private RoomEffect() {
            super(MobEffectCategory.BENEFICIAL, 0xF2D16B);
        }

        // nothing cures it: milk would otherwise take the room away, and the absorption with it
        @Override
        public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        }
    }
}
