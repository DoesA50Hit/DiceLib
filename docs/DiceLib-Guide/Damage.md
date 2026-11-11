# Damage

`Hits` deals damage and records where it came from, as `Heals` does for healing ([[Healing]]). `Shots` fires projectiles that hit through it. Both are in `combat` and run on the server.

## Hits

Every hit has a source. The source is the caster, or something that belongs to them such as a projectile, a prop or a summon. DiceLib finds the living entity behind the source and makes it the attacker, so a hit from a floating sword counts as its owner's hit and its owner gets the kill. The damage goes through vanilla's `hurt`, so armour, enchantments, hurt time and the damage events all apply.

```java
// 6 damage that counts as the caster's hit
Hits.of(caster, target, 6)
        .knockback(0.8, 0.3)      // shoved 0.8 back from the caster and 0.3 up
        .fx(MyMod.id("slash"), Fx.Fallback.burst(ParticleTypes.CRIT, 0.4f, 8))   // played on the target if the hit lands
        .hit();                   // deals it, and returns what the target lost

// The source can be a prop. The sword's owner is then the attacker and gets the kill.
Hits.of(sword, target, 4).type(DamageTypes.MAGIC).hit();
```

| Call | Does |
|---|---|
| `Hits.of(source, target, amount)` | a hit to set up, then `hit()` |
| `Hits.hit(source, target, amount)` | the same in one call |
| `times(multiplier)` | scales the amount |
| `type(damageType)` | the damage type. Left out, it is a player's attack when the attacker is a player, a mob's attack for any other attacker, and magic with no attacker |
| `throughHurtTime()` | lands even while the target is still red from the last hit. Use it for hits that come several times a second |
| `knockback(back, up)` | a shove away from the source when it lands, on top of the small one vanilla gives |
| `fx(id, fallback)`, `sound(sound, volume, pitch)` | played on the target when it lands |
| `onHit(dealt -> ...)` | runs with what the target lost |
| `hit()` | hits, and returns what the target lost in health and absorption. 0 when cancelled, blocked or still in hurt time |

## Events

Both are posted on `NeoForge.EVENT_BUS`. `getEntity()` is the entity that was hit.

| Event | When |
|---|---|
| `Hits.HitEvent.Hit` | before anything is hurt. Cancel it, or change the amount with `setAmount` |
| `Hits.HitEvent.Landed` | after, with what the target lost. Not posted when the hit didn't land |

`getSource()` is the entity the hit came from, and `getAttacker()` is the living entity behind it.

```java
// a quarter of what the wearer deals comes back as health
@SubscribeEvent
static void onLanded(Hits.HitEvent.Landed event) {
    // getAttacker is the living entity behind the hit, also when a prop or a shot of theirs dealt it
    if (event.getAttacker() instanceof Player player && wearsLeechRing(player)) {
        Heals.heal(player, player, event.getAmount() / 4);   // getAmount is what the target actually lost
    }
}
```

Only hits made through `Hits` post these. Vanilla's own damage events cover everything else.

## Shots

`Shots` fires a projectile for an ability. A shot is an entity with no model that the server flies along a line, an arc or after a target. Players see its trail. It hits whatever its path passes, by its owner's relations ([[Targeting]]), and its damage goes through `Hits`, so the owner is the attacker.

```java
// a bolt from the caster's hand that goes through two enemies and bursts where it ends
Shots.from(caster, BodyPoints.hand(caster, true), caster.getLookAngle().scale(1.5))   // 1.5 blocks a tick
        .range(24)                // ends after 24 blocks
        .pierce(2)                // passes through two enemies and stops in the third
        .damage(5)                // each one takes 5, with the caster as the attacker
        // a shot has no model, so the trail is what players see
        .trail(MyMod.id("bolt"), Fx.Fallback.line(ParticleTypes.ELECTRIC_SPARK))
        // onEnd runs once however the shot ends, with the shot still at that spot
        .onEnd((shot, end) -> Fx.at(level, MyMod.id("burst"), shot.position(), 1, Fx.Fallback.burst(ParticleTypes.CRIT, 0.4f, 12)))
        .fire();                  // nothing flies until fire()

// a slow missile fired straight up that turns toward its target
Shots.from(caster, BodyPoints.chest(caster), new Vec3(0, 0.5, 0))
        .homing(target, 12)       // turns up to 12 degrees a tick
        .life(100)                // gives up after 5 seconds if it never arrives
        .damage(8)
        .fire();
```

| Call | Does |
|---|---|
| `Shots.from(owner, from, velocity)` | a shot from a point, flying `velocity` blocks per tick. `Shots.from(level, null, from, velocity)` makes one with no owner, which hits anyone |
| `range(blocks)`, `life(ticks)` | how far it flies (32) and how long it lasts at most (200) |
| `gravity(perTick)` | makes it fall, for an arc. An arrow's is 0.05 |
| `homing(target, degreesPerTick)` | turns toward the target each tick, keeping its speed |
| `width(blocks)` | how close its path must pass a hitbox to hit (0.25) |
| `pierce(count)` | how many entities it passes through before the one that stops it (0) |
| `relation(relation)` | who it hits, seen from its owner (`NON_ALLIES`). Never the owner |
| `throughBlocks()` | blocks don't stop it |
| `damage(amount)` | hurts each entity it hits, through `Hits` |
| `trail(id, fallback)`, `trail(id, fallback, every)` | a line effect along the stretch it flew, every tick or every `every` ticks |
| `onHit((shot, target) -> ...)` | runs for each entity it hits, after the damage |
| `onBlock((shot, blockHit) -> ...)` | runs when a block stops it |
| `onTick(shot -> ...)` | runs every tick it flies, after it has moved, for particles or a sound a trail can't give |
| `onEnd((shot, end) -> ...)` | runs once when it ends, with the shot still at that spot |
| `fire()` | adds it to the level |

`Shots.End` is `RANGE`, `EXPIRED`, `BLOCK`, `HIT` (it ran out of pierce) or `STOPPED` (`shot.stop()` was called). A flying shot can be redirected with `shot.velocity(vector)`. Shots are never saved.

---

← Previous: [[Zones]] · Next: [[Healing]] →
