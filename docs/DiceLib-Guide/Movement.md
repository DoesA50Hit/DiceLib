# Movement

`Dashes` moves a caster along a path over a few ticks. `Teleports` moves an entity in one jump, to an exact position, to the nearest safe spot, or as a blink. Both are in `motion` and run on the server.

## Dashes

A dash carries the caster for a few ticks, for a lunge or a pounce. Each tick it sets the caster's motion (and tells a player's client), keeps the fall from counting, and at the end stops the caster where it is. `start()` takes the first step immediately and returns the `Scheduler` task for the rest. A caster has one dash at a time, so starting another interrupts the one that is running. `Dashes.cancel(caster)` stops a dash on the spot and `Dashes.dashing(caster)` asks whether one is running.

```java
public final class ExampleLunge {
    // A short dash along the ground that cuts each enemy in the way once.
    public static void lunge(LivingEntity caster) {
        // flatLook is the way the caster looks with the up and down taken out, one block long.
        // Scaled by 0.9 it becomes the dash's speed in blocks a tick.
        Vec3 velocity = BodyPoints.flatLook(caster).scale(0.9);
        Dashes.along(caster, velocity, 6)                 // 6 ticks at 0.9 is a bit over 5 blocks
                // runs once for each non-ally the caster passes through
                .onHit(Targets.Relation.NON_ALLIES, hit -> hit.hurt(caster.damageSources().mobAttack(caster), 4))
                .start();                                 // the dash only begins here
    }

    // A leap at the enemy the caster looks at, with a heavy blow on arrival. False when there is no one to leap at,
    // so the ability can skip its cooldown.
    public static boolean pounce(LivingEntity caster) {
        LivingEntity target = Targets.lookedAt(caster, 12, Targets.Relation.NON_ALLIES);   // within 12 blocks
        if (target == null) return false;
        Dashes.to(caster, target, 1.2, 20)                // 1.2 blocks a tick, given up after 20 ticks
                // runs when the caster touches the target, and the dash ends there
                .onContact(hit -> {
                    // hurt returns false when the damage was blocked, and then there is no shove
                    if (hit.hurt(caster.damageSources().mobAttack(caster), 8)) {
                        Knockback.away(hit, caster.position(), 0.8, 0.3);   // 0.8 back from the caster, 0.3 up
                    }
                })
                .start();
        return true;
    }

    private ExampleLunge() {
    }
}
```
| Call | Does |
|---|---|
| `Dashes.along(caster, velocity, ticks)` | along `velocity` (blocks a tick) for `ticks`. With no up or down in it, gravity still pulls, so the caster runs along the ground. With some, it flies that way |
| `Dashes.to(caster, target, speed, maxTicks)` | straight at `target`, `speed` blocks a tick, for at most `maxTicks`. It stops on touching the target, or when the target is gone |
| `onHit(relation, target -> ...)` | once for each entity of `relation` to the caster that the dash sweeps through |
| `onContact(target -> ...)` | `to` only: when the caster reaches the target. The dash stops there |
| `onFinish(caster -> ...)` | when the dash ends by itself (time up, contact, the target gone, a wall). Not when it is interrupted |
| `onEnd((caster, end) -> ...)` | however it stops, once, with the reason: `DONE`, `CONTACT`, `TARGET_GONE`, `WALL`, or `INTERRUPTED` for another dash, `Dashes.cancel`, or the caster dying or leaving. Use it to undo whatever the dash granted |
| `stopAtWalls()` | ends the dash when the caster runs into a block, instead of pushing against it until time is up |

```java
// untouchable for the length of the dash
caster.setInvulnerable(true);
Dashes.along(caster, BodyPoints.flatLook(caster).scale(0.9), 8)
        .stopAtWalls()            // ends at a wall, where it would otherwise keep pushing against it
        // onEnd runs however the dash ends, so the flag is never left on
        .onEnd((dasher, end) -> dasher.setInvulnerable(false))
        .start();
```

## Teleports

Every teleport goes through `Teleports.MoveTeleport`, an `EntityTeleportEvent`, so anything listening for ender pearls and chorus fruit hears about these too, and can change the target or cancel. `Teleports.to(entity, target)` and `Teleports.blink(entity, distance)` set one up, and `go()` sends it. `go()` returns where the entity landed, or empty when the teleport was refused or cancelled. An entity that is riding something gets off first, as with an ender pearl.

### Exact or safe

A plain `to(...).go()` lands exactly where it is sent, even inside a wall or in mid-air. `safe()` asks for a safe spot instead: one the entity fits in, with ground under it, and with no fluid and nothing from the `dicelib:unsafe_to_land` block tag in or under it. The tag holds fire, campfires, lava, magma, cactus, berry bushes, wither roses, powder snow, cobwebs and portals, and a datapack can add more. The search tries the target first, then whole blocks outward along x and z and up and down, nearest first, and lower before higher on a tie.

```java
// to a marked spot
Teleports.to(caster, marked)
        .safe(4, 3)               // the nearest safe spot within 4 blocks along x and z and 3 up or down
        .orAnyway()               // or exactly the marked spot when there is none
        .facingToward(target.getEyePosition())   // turned to look at the target on arrival
        .go();

// Straight down onto the ground under a point in the air. safe(0, 8) only searches up and down.
Optional<Vec3> landed = Teleports.to(caster, point).safe(0, 8).go();   // empty when there was no ground within 8 blocks
```
| Call | Does |
|---|---|
| `safe()` | the nearest safe spot within `SAFE_SPREAD` (3) blocks along x and z and `SAFE_REACH` (4) up or down. Refused when there is none |
| `safe(spread, reach)` | the same with its own sizes. A spread of 0 looks only straight up and down, a reach of 0 only at the same height |
| `orAnyway()` | with `safe`: the exact spot when no safe one turns up, instead of refusing |
| `in(level)` | `to` only: into another level. The search runs there, and a mob arrives as a new entity |
| `facing(yaw, pitch)`, `facingToward(point)` | turned on arrival. Without either it keeps its rotation |
| `stop()` | stops all motion on arrival, so a fall or a dash doesn't carry through |
| `fx(depart, arrive, fallback)` | effects where it left and where it arrived ([[Effects and Animations]]). Either id may be null |
| `trail(id, fallback)` | a line effect from where it left to where it arrived, at chest height |
| `sound(sound, volume, pitch)` | played at both ends |
| `preview()` | where it would land now, without moving or posting the event, for a marker or a check |
| `go()` | teleports, and returns where it landed |

### Blinks

`blink(entity, distance)` jumps up to `distance` blocks the way the entity looks. The first wall stops it, and it lands at the farthest safe spot along the way that it can see from where it stood. A blink is safe by default and searches straight up and down within `SAFE_REACH`, so it follows the ground over steps and drops.

| Call | Does |
|---|---|
| `flat()` | along the way it looks with the up and down taken out |
| `direction(vector)` | along any direction instead of the look |
| `throughBlocks()` | walls don't stop it, so it can land on the far side of one |
| `anywhere()` | lands at the far end exactly, safe or not. Through a wall, that can be inside it |
| `atLeast(blocks)` | refused when it can't get that far (default 1) |
| `safe(spread, reach)`, `orAnyway()` and the rest | as for `to`. `safe(1, 4)` lets a blink step sideways round a pillar |

### Other teleports

```java
// Each method returns false when the caster went nowhere, so the ability that called it can skip its cost.
public final class ExampleShadowStep {
    // Up to 8 blocks ahead, with a trail of smoke behind.
    public static boolean step(LivingEntity caster) {
        return Teleports.blink(caster, 8)
                .flat()               // straight ahead, even when the caster looks up or down
                .throughBlocks()      // a wall doesn't stop it. It lands on the far side, never inside
                .stop()               // no leftover falling or dashing speed after landing
                .trail(MyMod.id("streak"), Fx.Fallback.line(ParticleTypes.SMOKE))   // drawn from start to end
                .sound(SoundEvents.ENDERMAN_TELEPORT, 0.8f, 1.4f)                   // volume 0.8, pitch 1.4
                .go()                 // teleports, and returns where the caster landed
                .isPresent();         // empty means no safe spot was found or another mod cancelled it
    }

    // Behind the enemy the caster looks at, turned to face its back.
    public static boolean ambush(LivingEntity caster) {
        LivingEntity target = Targets.lookedAt(caster, 16, Targets.Relation.NON_ALLIES);
        if (target == null) return false;
        return Teleports.behind(caster, target, 1.5)      // 1.5 blocks behind the target
                // no effect where the caster left (null), and a puff of smoke where it arrives
                .fx(null, MyMod.id("ambush"), Fx.Fallback.burst(ParticleTypes.SMOKE, 0.4f, 12))
                .go().isPresent();
    }

    // Trades places with the enemy the caster looks at. Both keep the way they were facing.
    public static boolean swap(LivingEntity caster) {
        LivingEntity target = Targets.lookedAt(caster, 16, Targets.Relation.NON_ALLIES);
        return target != null && Teleports.swap(caster, target);
    }

    private ExampleShadowStep() {
    }
}
```
| Call | Does |
|---|---|
| `Teleports.behind(entity, target, distance)` | a `to` set up `distance` blocks behind the target's back, turned to face it, safe within 1 block |
| `Teleports.toLookedAt(entity, range)` | a `to` onto what the entity looks at: the top of a block, the floor in front of a wall, an entity's feet, or the end of the range. Safe within 1 block |
| `Teleports.swap(a, b)` | swaps where two entities stand. Both teleports are posted first. If a listener cancels either, nobody moves and it returns false |
| `Teleports.teleport(entity, target)` | the same as `to(entity, target).go()` |
| `Teleports.toSpawn(player)` | to the player's respawn point in any dimension, without using up an anchor charge. Posts no event |
| `Teleports.isSafe(entity, level, feet)` | whether the entity could stand with its feet there |
| `Teleports.findSafe(entity, level, near, spread, reach)` | the nearest safe spot, by the same search `safe` uses |
| `Teleports.randomSafeSpot(entity, centre, min, max)` | a safe spot at a random point `min` to `max` blocks out along the ground |
| `Teleports.standableNear(level, pos)` | a spot with ground and two blocks of room at `pos`'s x and z, within 3 up or down, for code with no entity at hand |

---

← Previous: [[Healing]] · Next: [[Props]] →
