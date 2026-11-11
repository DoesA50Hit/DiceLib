package org._50dozenhits.dice_library.combat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import org._50dozenhits.dice_library.visual.Fx;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Damage that remembers where it came from, the way Heals does for healing: the entity it came from (a caster, a
 * projectile, a prop, a summon) and, through that, the living attacker behind it, who gets the kill. Anything an
 * ability hurts can go through here, so a "when you hit" rule can listen to HitEvent.Landed and read getSource or
 * getAttacker. It is vanilla's hurt underneath, so armour, enchantments, hurt time and every damage event apply as
 * usual. Server side.
 *
 * <pre>{@code
 * Hits.of(caster, target, 6).knockback(0.8, 0.3).fx(fx("slash"), Fx.Fallback.burst(ParticleTypes.CRIT, 0.4f, 8)).hit();
 * Hits.of(sword, target, 4).type(DamageTypes.MAGIC).hit();      // the floating sword's owner is the attacker
 * }</pre>
 */
public final class Hits {
    /**
     * {@code source} is the entity the hit comes from: the caster, or a projectile, prop or summon of theirs. Null for damage
     * from nowhere in particular.
     */
    public static Hit of(@Nullable Entity source, LivingEntity target, float amount) {
        return new Hit(source, target, amount);
    }

    /**
     * What the target lost in health and absorption, after armour and every rule. 0 when nothing landed.
     */
    public static float hit(@Nullable Entity source, LivingEntity target, float amount) {
        return of(source, target, amount).hit();
    }

    public static final class Hit {
        @Nullable
        private final Entity source;
        private final LivingEntity target;
        private float amount;
        @Nullable
        private ResourceKey<DamageType> type;
        private boolean throughHurtTime;
        private double back;
        private double up;
        private boolean knocks;
        @Nullable
        private ResourceLocation fx;
        @Nullable
        private Fx.Fallback fxFallback;
        @Nullable
        private SoundEvent sound;
        private float volume = 1;
        private float pitch = 1;
        @Nullable
        private Consumer<Float> onHit;

        private Hit(@Nullable Entity source, LivingEntity target, float amount) {
            this.source = source;
            this.target = target;
            this.amount = amount;
        }

        public Hit times(float multiplier) {
            amount *= multiplier;
            return this;
        }

        /**
         * the damage type (DamageTypes.MAGIC, or your own). Left out, it is a player's attack when the attacker
         * is a player, a mob's attack for any other attacker, and magic with no attacker
         */
        public Hit type(ResourceKey<DamageType> type) {
            this.type = type;
            return this;
        }

        /**
         * lands even while the target is still flashing red from the last hit, for hits that come several a second
         */
        public Hit throughHurtTime() {
            this.throughHurtTime = true;
            return this;
        }

        /**
         * a shove away from the source when the hit lands (Knockback.away), on top of the small one vanilla gives
         */
        public Hit knockback(double back, double up) {
            this.back = back;
            this.up = up;
            this.knocks = true;
            return this;
        }

        /**
         * played on the target when the hit lands, sized to the target
         */
        public Hit fx(ResourceLocation id, Fx.Fallback fallback) {
            this.fx = id;
            this.fxFallback = fallback;
            return this;
        }

        /**
         * played at the target when the hit lands
         */
        public Hit sound(SoundEvent sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
            return this;
        }

        /**
         * runs with what the target lost when the hit landed
         */
        public Hit onHit(Consumer<Float> onHit) {
            this.onHit = onHit;
            return this;
        }

        /**
         * Hits. Returns what the target lost in health and absorption. 0 when cancelled, blocked or it never landed.
         */
        public float hit() {
            if (amount <= 0 || !target.isAlive() || target.level().isClientSide()) return 0;
            HitEvent.Hit event = NeoForge.EVENT_BUS.post(new HitEvent.Hit(target, source, amount));
            if (event.isCanceled() || event.getAmount() <= 0) return 0;
            LivingEntity attacker = Heals.healerOf(source);
            ResourceKey<DamageType> key = type != null ? type
                    : attacker instanceof Player ? DamageTypes.PLAYER_ATTACK : attacker != null ? DamageTypes.MOB_ATTACK : DamageTypes.MAGIC;
            DamageSource damage = new DamageSource(target.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key),
                    source, attacker != null ? attacker : source);
            if (throughHurtTime) target.invulnerableTime = 0;
            float before = target.getHealth() + target.getAbsorptionAmount();
            if (!target.hurt(damage, event.getAmount())) return 0;
            float dealt = Math.max(0, before - (target.getHealth() + target.getAbsorptionAmount()));
            NeoForge.EVENT_BUS.post(new HitEvent.Landed(target, source, dealt));
            if (knocks && source != null && target.isAlive()) Knockback.away(target, source.position(), back, up);
            if (fx != null && fxFallback != null) Fx.on(target, fx, target.getBbWidth() + 0.4f, fxFallback);
            if (sound != null) Fx.sound(target, sound, volume, pitch);
            if (onHit != null) onHit.accept(dealt);
            return dealt;
        }
    }

    /**
     * getEntity() is the entity that was hit.
     */
    public abstract static class HitEvent extends LivingEvent {
        @Nullable
        private final Entity source;

        protected HitEvent(LivingEntity target, @Nullable Entity source) {
            super(target);
            this.source = source;
        }

        /**
         * the entity the hit came from: a caster, or a projectile, prop or summon of theirs. Null for damage from
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
        public LivingEntity getAttacker() {
            return Heals.healerOf(source);
        }

        /**
         * Before anything is hurt. Cancel it, or change how much it does.
         */
        public static class Hit extends HitEvent implements ICancellableEvent {
            private float amount;

            public Hit(LivingEntity target, @Nullable Entity source, float amount) {
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
         * After, with what the target lost in health and absorption. Not posted when the hit didn't land.
         */
        public static class Landed extends HitEvent {
            private final float amount;

            public Landed(LivingEntity target, @Nullable Entity source, float amount) {
                super(target, source);
                this.amount = amount;
            }

            public float getAmount() {
                return amount;
            }
        }
    }

    private Hits() {
    }
}
