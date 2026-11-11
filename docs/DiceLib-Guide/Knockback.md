# Knockback

`Knockback` shoves, pulls and leashes. Every push scales by the target's knockback resistance, so at 1 the target doesn't move, and a push on a player reaches that player's client. A shove along the ground (`away`, `toward`, `along`, `push`) posts vanilla's `LivingKnockBackEvent` first, so a mod that cancels or changes a sword's knockback does the same to these. `pull` and `leash` run every tick and don't post it.

| Call | Does |
|---|---|
| `Knockback.away(target, from, back, up)` | away from `from` along the ground by `back`, and up by `up` (roughly blocks per tick) |
| `Knockback.toward(target, to, strength, up)` | toward `to`, which pulls the target in |
| `Knockback.along(target, direction, strength, up)` | along `direction` on the ground (its up and down is ignored), and up by `up`. Use it for wind, or any shove that has no source point |
| `Knockback.push(target, velocity)` | a push along any velocity |
| `Knockback.pull(entity, point, strength)` | any entity, projectiles included, drawn toward a point, faster the further away (at most `strength` blocks a tick), slowing what it was doing. Called every tick, it makes a vortex. Nothing within half a block moves |
| `Knockback.leash(target, anchor, radius)` | called every tick: past `radius` from `anchor`, the leash stops the way out and draws the target back, harder the further past |

```java
// a shockwave: every non-ally within 4 blocks is thrown 1.2 back from the caster and 0.4 up
for (LivingEntity target : Targets.around(caster, 4, Targets.Relation.NON_ALLIES)) {
    Knockback.away(target, caster.position(), 1.2, 0.4);
}

// a vortex for 3 seconds where the caster looks
Vec3 centre = Targets.eyeLine(caster, 16).getLocation();   // the block or entity looked at, or the end of the line
// Every tick for 60 ticks. The task belongs to the caster and stops if they die.
Scheduler.repeat(caster, 1, 60, (owner, tick) -> {
    for (LivingEntity target : Targets.around(owner, centre, 8, Targets.Relation.NON_ALLIES)) {
        Knockback.pull(target, centre, 0.3);   // at most 0.3 blocks a tick, faster the further out they are
    }
});
```
[[Movement#Dashes]] moves the caster itself.

---

← Previous: [[Targeting]] · Next: [[Zones]] →
