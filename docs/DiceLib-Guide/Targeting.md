# Targeting

`Targets` finds the entities an ability reaches. It searches along the caster's eye line or in an area, a line, a cone or a chain, and it can filter the result to allies or enemies. The caster can be a player or a mob.

## Eye line

| Method | Returns |
|---|---|
| `eyeLine(caster, range)` | a `HitResult`: the nearest entity on the eye line (`EntityHitResult`), else the block that stops the line (`BlockHitResult`), else a miss |
| `lookedAt(caster, range, relation)` | the living entity looked at, when alive and of the relation, otherwise null |

Blocks in the way hide whatever is behind them. The ray stops at the first block with a collision shape, and fluids don't count. Spectators, the caster, and entities that can't be picked are skipped.

The line uses the caster's view direction, the head rotation for living entities. A real player's head follows the camera, so the line points where the player looks.

```java
// an enemy in front of the caster within 16 blocks
LivingEntity enemy = Targets.lookedAt(caster, 16, Targets.Relation.NON_ALLIES);
if (enemy != null) enemy.hurt(caster.damageSources().mobAttack(caster), 5f);
```
An ability that also works on blocks uses the full hit result:

```java
HitResult hit = Targets.eyeLine(caster, 20);
switch (hit) {
    case EntityHitResult entityHit -> {
        Entity struck = entityHit.getEntity();
        struck.igniteForSeconds(3);
    }
    case BlockHitResult blockHit when blockHit.getType() == HitResult.Type.BLOCK -> {
        BlockPos above = blockHit.getBlockPos().relative(blockHit.getDirection());
        if (caster.level().getBlockState(above).isAir()) caster.level().setBlockAndUpdate(above, Blocks.FIRE.defaultBlockState());
    }
    default -> {
        // a miss: hit.getLocation() is the end of the line
    }
}
```
This steps to where the caster looks, stopping half a block short of the first wall:

```java
Vec3 end = Targets.eyeLine(caster, 12).getLocation();
Vec3 back = caster.getLookAngle().scale(-0.5);
caster.teleportTo(end.x + back.x, end.y + back.y, end.z + back.z);
```
[[Movement#Teleports]] does the same with safe landing spots and effects.

## Areas

| Method | Returns |
|---|---|
| `inBox(origin, box, relation)` | living entities inside an `AABB`, not counting the origin |
| `around(origin, radius, relation)` | living entities within `radius` blocks of the origin's position |
| `around(origin, point, radius, relation)` | living entities whose feet are within `radius` of a point, the relation seen from `origin`. Never the origin |
| `touching(origin, point, radius, relation)`, `touching(level, point, radius, predicate)` | the same two searches by the hitbox instead of the feet: anyone whose hitbox comes within `radius` of the point, so a big mob whose side is in reach counts |
| `around(level, point, radius, predicate)` | every living entity within `radius` of a point that the predicate lets through, for searches with no caster |
| `cylinder(origin, base, radius, height, relation)` | everyone standing in an upright cylinder: `radius` around `base` along the ground, from `base` up `height` blocks |
| `projectilesAround(origin, point, radius, relation)` | projectiles in flight near a point, judged by who shot them. `NON_ALLIES` are those fired by anyone not allied with `origin`, and those with no shooter |
| `nearest(list, from)`, `nearest(list, from, count)` | the nearest of a list, or the `count` nearest in order |
| `byDistance(list, from)` | a copy of a list, nearest first |
| `isAlly(a, b)` | true for the same entity, when either side considers the other allied, when their owners are allied, or when an `allyRule` says so |
| `matches(origin, other, relation)` | the relation check alone |
| `targetable(entity)` | whether an ability can pick the entity at all: alive, not a spectator, not in the `dicelib:untargetable` tag |
| `allyRule(rule)` | adds a way for two entities to be allies. Register once at startup |

`Relation` is `ALLIES`, `NON_ALLIES` or `ANY`. "Allied" follows vanilla's `isAlliedTo`: the same scoreboard team, a tamed animal and its owner, or anything a mod adds to that check. The check runs both ways. It then runs again for whoever owns each of the two (a projectile's shooter, a summon's caster), so two summons of one caster are allies, and a summon is an ally of its caster's team-mates.

Vanilla doesn't count players without a team as allies. If your mod has its own parties, add a rule:

```java
Targets.allyRule((a, b) -> a instanceof Player x && b instanceof Player y && Parties.same(x, y));
```

Every search skips entities that aren't `targetable`. The entity type tag `dicelib:untargetable` lists armour stands and DiceLib's entity copies. Add your own scenery entities to it from a datapack (`data/dicelib/tags/entity_type/untargetable.json`).

```java
// every non-ally within 5 blocks takes 4 damage
for (LivingEntity target : Targets.around(caster, 5, Targets.Relation.NON_ALLIES)) {
    target.hurt(caster.damageSources().mobAttack(caster), 4f);
}

// heal every ally within 8 blocks, the caster too
Heals.heal(caster, caster, 2f);
for (LivingEntity ally : Targets.around(caster, 8, Targets.Relation.ALLIES)) {
    Heals.heal(caster, ally, 2f);
}
```
## Lines, cones and chains

| Method | Returns |
|---|---|
| `line(caster, range, width, relation)` | everyone the eye line passes within `width` blocks of, nearest first, up to the first block in the way |
| `cone(caster, range, angle, relation)` | everyone within `range` the caster can see, within `angle / 2` degrees of where it looks (`angle` is the full width) |
| `chain(caster, first, count, hop, relation)` | `first`, then the nearest not-yet-hit entity within `hop` blocks of the last one, `count` total. The caster is never included |
| `chain(caster, first, count, hop, relation, also)` | the same, hopping only to those the predicate `also` lets through |
| `segment(origin, from, to, width, relation)` | everyone within `width` blocks of the segment from `from` to `to`, nearest to `from` first. Blocks don't stop it |
| `blockStop(origin, from, to)` | where that segment hits its first block, or `to` |

`segment` and `blockStop` are for beams that don't start at the caster's eyes, such as one from a turret or from the sky.

```java
// a beam from 20 blocks up, straight down to the ground where the caster looks
Vec3 spot = Targets.eyeLine(caster, 24).getLocation();
Vec3 sky = spot.add(0, 20, 0);
Vec3 ground = Targets.blockStop(caster, sky, spot.add(0, -20, 0));
for (LivingEntity target : Targets.segment(caster, sky, ground, 0.5, Targets.Relation.NON_ALLIES)) {
    target.hurt(caster.damageSources().mobAttack(caster), 5f);
}
```
```java
// a lance that pierces everyone in a line
for (LivingEntity target : Targets.line(caster, 16, 0.5, Targets.Relation.NON_ALLIES)) {
    target.hurt(caster.damageSources().mobAttack(caster), 6f);
}

// a 90 degree cone of steam, 8 blocks long
for (LivingEntity target : Targets.cone(caster, 8, 90, Targets.Relation.NON_ALLIES)) {
    target.hurt(caster.damageSources().mobAttack(caster), 3f);
}

// a ricochet between up to 4 enemies within 6 blocks of each other
LivingEntity first = Targets.lookedAt(caster, 32, Targets.Relation.NON_ALLIES);
if (first != null) {
    for (LivingEntity target : Targets.chain(caster, first, 4, 6, Targets.Relation.NON_ALLIES)) {
        target.hurt(caster.damageSources().mobAttack(caster), 4f);
    }
}
```
For any other shape, call `inBox` and filter the result. These searches are built the same way.

This puts two players on a scoreboard team, as vanilla's `/team` does, which makes them allies:

```java
Scoreboard scoreboard = server.getScoreboard();
PlayerTeam team = scoreboard.getPlayerTeam("party");
if (team == null) team = scoreboard.addPlayerTeam("party");
scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
scoreboard.addPlayerToTeam(target.getScoreboardName(), team);
boolean allied = Targets.isAlly(player, target);   // true
```

## Control

`Control` takes a mob over for a while, for a charm or a mind control. It works on any `Mob`, including ones vanilla can't tame, and it doesn't touch vanilla taming, so the mob is unchanged when the control ends.

```java
// the zombie fights for the caster for 30 seconds
if (!Control.of(caster, zombie).life(600).start()) {
    // it can't be controlled, or another mod refused
}
```

| Call | Does |
|---|---|
| `Control.of(owner, mob)` | control to set up, then `start()`. `start()` returns false when it is refused |
| `life(ticks)` | how long it lasts (600 by default) |
| `allowBosses()` | lets it work on bosses |
| `onEnd((mob, end) -> ...)` | runs when it ends, with `EXPIRED`, `RELEASED`, `REPLACED` or `GONE` (the mob died or was removed). Not kept through a restart |
| `Control.release(mob)` | ends it now |
| `Control.isControlled(mob)`, `owner(mob)`, `remaining(mob)` | whether it is controlled, by whom, and for how many more ticks |
| `Control.controlledBy(owner)` | the loaded mobs in the owner's level that it controls |
| `Control.canControl(owner, mob, bosses)` | whether `start()` would accept the mob |

Three things refuse control:

- The mob's type is in the `c:bosses` entity type tag, which holds the wither and the ender dragon. `allowBosses()` skips this check.
- The mob's type is in the `dicelib:no_tame` entity type tag. Add types to it from a datapack (`data/dicelib/tags/entity_type/no_tame.json`).
- The mob carries the tag `noTame`, as given by `/tag @e[...] add noTame`.

Nothing in code bypasses the last two.

While a mob is controlled it drops its target and can't target its controller or the controller's allies. Once a second it picks up the controller's fight, going after whoever hurt the controller or whoever the controller hurt, and when it has nothing to fight it walks back to the controller. `Targets.isAlly` treats it as the controller's, so your abilities count it as an ally. A mob with no attack only follows. Mobs that choose targets through a brain, such as piglins and wardens, stop attacking the controller but may not join its fights.

Control is saved with the mob and counts game time. `Control.ControlEvent.Start` fires before it is taken and can be cancelled, for an immunity or a resist roll. `Control.ControlEvent.End` fires after it ends, with the reason.

---

← Previous: [[Setup]] · Next: [[Knockback]] →
