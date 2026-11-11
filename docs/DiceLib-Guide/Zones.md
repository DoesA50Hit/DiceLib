# Zones

`Zones` makes an area that lasts and pulses, such as a healing spring or a burning circle. `Zones.at(level, centre, radius)` sets one up at a point (the middle of its floor), and `Zones.on(entity, radius)` sets up one that follows the entity and ends with it. Each pulse plays the zone's effect stretched to its radius, plays its sound, and runs its rules on whoever stands in it. The first pulse happens immediately. `start()` returns the `Scheduler` task for the rest, and cancelling it ends the zone early. Zones are not saved, so a server stop ends them.

```java
public final class MendingSpring {
    // A healing spring at the caster's feet for 5 seconds. Call it from an item's use or a boss's attack.
    public static void place(LivingEntity caster) {
        if (!(caster.level() instanceof ServerLevel level)) return;   // zones run on the server
        Zones.at(level, caster.position(), 4)             // a circle 4 blocks in radius
                .every(20)                                // one pulse a second
                .lasting(100)                             // for 5 seconds. The first pulse is immediate
                .fx(MyMod.id("spring"), Fx.Fallback.ring(ParticleTypes.SPLASH, 4))   // played at each pulse
                // Each pulse heals the caster and every ally in the circle by 1. What doesn't fit under their max
                // health stays as absorption for 100 ticks.
                .onAllies(caster, (ally, pulse) -> Heals.of(caster, ally, 1).overheal(100).heal())
                // Everyone else in it is slowed. 30 ticks is longer than the gap between pulses, so the slow holds for
                // as long as they stand there.
                .onEnemies(caster, (enemy, pulse) -> enemy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1), caster))
                .start();
    }

    private MendingSpring() {
    }
}
```
| Call | Does |
|---|---|
| `every(ticks)` | ticks between pulses (default 20) |
| `times(pulses)` | the total number of pulses, counting the immediate first one (default 1) |
| `lasting(ticks)` | one pulse now and one every `every` ticks until `ticks` have passed |
| `follow(entity)` | moves with the entity, at its feet, and ends when it dies or leaves |
| `height(blocks)` | an upright cylinder from the floor up, instead of a ball |
| `fx(id, fallback)`, `sound(sound, volume, pitch)` | played at the middle of the floor each pulse ([[Effects and Animations]]) |
| `onEnemies(caster, (target, pulse) -> ...)` | each pulse, on everyone in it not allied with `caster`. Never the caster |
| `onAllies(caster, (ally, pulse) -> ...)` | each pulse, on `caster` and its allies standing in it |
| `on(predicate, (entity, pulse) -> ...)` | each pulse, on everyone in it the predicate lets through |
| `onPulse((centre, pulse) -> ...)` | each pulse, with where the zone is now, for anything that isn't a living entity, such as blocks or items |
| `onEnter(entity -> ...)` | for each entity found in the zone at a pulse that wasn't there the pulse before, the first pulse included |
| `onExit(entity -> ...)` | for each entity gone since the pulse before, and for everyone still inside at the last pulse. Not when the task is cancelled or the followed entity goes |
| `hitboxes()` | counts anyone whose hitbox reaches into the ball, not only feet inside it. A zone with a `height` still goes by the feet |
| `center()`, `inside()` | where it is and who stands in it, at any time |

`onEnter` and `onExit` are checked once per pulse, so they can be up to `every` ticks late. They run for every entity in the zone, allies and enemies alike, so filter inside the callback if that matters.

```java
// a slowing mire for 10 seconds: slowed while inside, freed on the way out
Zones.at(level, centre, 4)
        .every(5)                 // checked 4 times a second, so stepping in or out is noticed quickly
        .lasting(200)
        // onEnter runs once for each entity that steps in. -0.4 makes it 40% slower.
        .onEnter(entity -> While.modifier(entity, Attributes.MOVEMENT_SPEED, MyMod.id("mire"), -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, true))
        // onExit runs when it leaves, and for everyone still inside when the zone ends
        .onExit(entity -> While.modifier(entity, Attributes.MOVEMENT_SPEED, MyMod.id("mire"), 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, false))
        .start();
```
The pulse number counts from 0. A zone measures from the feet, so a ball reaches `radius` blocks up as well as out. Allies and enemies follow [[Targeting]]'s relations.

---

← Previous: [[Knockback]] · Next: [[Damage]] →
