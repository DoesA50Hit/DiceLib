# Effects and Animations

`Fx` plays visual effects by id from the server, `BodyPoints` gives the points on an entity they start from, and `PlayerAnims` plays player animations. All three are in `visual`.

## Effects

An effect is an id such as `mymod:spell/flare`, sent from the server to every client within 64 blocks (`Fx.RANGE`). DiceLib itself draws no effect files. Each client hands the id to the effect players its mods added ([[#Effect players]]), and the first one that knows the id plays it. Every call also gives a fallback, a vanilla particle pattern that the client draws when no effect player plays the id. This means an effect shows something before its file exists, and on a client with no effect library.

| Call | Plays |
|---|---|
| `Fx.at(level, id, point, scale, fallback)` | at a point, scaled evenly by `scale` (zone effects are made 1 block wide and played at their radius) |
| `Fx.at(level, id, point, scale, yaw, fallback)` | the same, turned to face `yaw` (degrees, like an entity's). Make the effect facing +Z |
| `Fx.on(entity, id, scale, fallback)` | at the entity's feet, following it while it plays |
| `Fx.line(level, id, from, to, fallback)` | from one point to another. An effect made 1 block long along +X is turned and stretched to fit |
| `Fx.line(level, id, from, to, thickness, fallback)` | the same, scaled by `thickness` across the line. A 1 block line turns an effect to face along it without stretching, for a circle that faces where someone looks |

`Fx.Fallback.burst`, `ring`, `sphere`, `column` and `line` make the fallback patterns. Their sizes are in blocks, and `scale` stretches only what an effect player draws. An effect played `on` an entity doesn't rotate with it, so something that must face an entity's travel plays as a line instead. Effects are one-shot. They can't loop and can't be removed once started, so for something that lasts, play a short effect again at an interval ([[#Sounds and repeated effects]]).

```java
// a ring at the target's feet, 6 blocks across
Fx.at(level, MyMod.id("bloom"), target.position(), 6, Fx.Fallback.ring(ParticleTypes.HAPPY_VILLAGER, 6));
```
`Fx.hideOnYourself(id)` marks an effect played `on` an entity that would fill the screen of the one it surrounds, such as a shell or a dome. That player's client skips it in first person, and everyone else still sees it. Call it once at startup, from either side.

## Effect players

An effect player turns ids into something drawn, usually with an effect library. Add one at client setup with `ClientFx.addPlayer`. Players get each effect in the order they were added. One returns true when it played the effect, or false to pass it on. When none plays it, the fallback does.

A sketch of one backed by Photon, whose effect files live at `assets/<ns>/fx/<path>.fx`:

```java
ClientFx.addPlayer((level, entity, payload) -> {
    // false passes the effect on to the next player, or to the fallback particles when there is none
    if (!FXHelper.listAllFX().contains(payload.id())) return false;
    FX fx = FXHelper.getFX(payload.id());
    if (entity != null) {
        EntityEffectExecutor executor = new EntityEffectExecutor(fx, level, entity, EntityEffectExecutor.AutoRotate.NONE);
        executor.setScale(new Vector3f(payload.scale(), payload.scale(), payload.scale()));
        executor.start();
    } else {
        new BlockEffectExecutor(fx, level, BlockPos.containing(payload.from())).start();
    }
    return true;
});
```
The payload carries everything the call gave: `id()`, `entity()` (-1 for none), `from()` and `to()` (equal except for lines and thrown effects), `scale()`, `yaw()` (NaN for unturned) and `fallback()`.

## Thrown effects

`Fx.fly(level, id, from, to, ticks, arc, fallback, onArrive)` throws an effect. The effect `id` (1 block long along +X, facing its travel, like a line effect) plays wherever it is each tick, rising `arc` blocks at the middle, and `onArrive` runs on the tick it lands. One packet starts the throw and each client steps it, so a long throw costs no more than a short one. Every tick plays a new copy, so keep the effect short, like a streak or a glint. `Fx.travel(from, to, speed)` returns the ticks something moving at `speed` blocks per tick takes.

```java
// a mote of light arcing from the target to the caster, healing when it lands
Vec3 from = BodyPoints.chest(target), to = BodyPoints.chest(caster);
int ticks = Fx.travel(from, to, 0.8);             // how long the trip takes at 0.8 blocks a tick
Fx.fly(level, MyMod.id("mote"), from, to, ticks,
        1.5,                                      // it rises 1.5 blocks in the middle of the arc
        Fx.Fallback.line(ParticleTypes.END_ROD),
        () -> Heals.heal(caster, caster, 2));     // runs on the tick it lands
```
For a thrown entity, play a trail `on` it instead ([[Props#Flying]]).

## Sounds and repeated effects

`Fx.sound(level, pos, sound, volume, pitch)` and `Fx.sound(entity, sound, volume, pitch)` play a sound for everyone near enough to hear it, as a player's sound. Both take a `SoundEvent` or a `Holder<SoundEvent>`. Pass a `SoundSource` after the sound to put it on another volume slider, for example `HOSTILE` for a monster's attack.

`Fx.onFor(entity, id, scale, fallback, every, ticks)` plays an effect on an entity now and again every `every` ticks for `ticks`, for something that lasts, such as a mark or an aura. It stops early when the entity dies or leaves, and returns the `Scheduler` task to stop it sooner.

```java
// a brand that smoulders on the target for 10 seconds, with a hiss when it lands
Fx.onFor(target, MyMod.id("brand"), 1, Fx.Fallback.burst(ParticleTypes.FLAME, 0.4f, 6),
        20, 200);                 // played again every 20 ticks for 200 ticks
Fx.sound(target, SoundEvents.FIRECHARGE_USE, 0.8f, 1.2f);   // volume 0.8, pitch 1.2
```
## Body points

`BodyPoints` gives points on an entity in world space, for where an effect, a beam or a thrown item starts.

| Call | Is |
|---|---|
| `BodyPoints.chest(entity)` | middle of the chest, 60 % up the bounding box |
| `BodyPoints.middle(entity)` | halfway up the bounding box, for any entity (an arrow, a prop) |
| `BodyPoints.ahead(entity, distance)` | a point straight in front of the chest, kept level |
| `BodyPoints.hand(entity, mainHand)` | roughly where the hand is, with the arm pointing where it looks |
| `BodyPoints.shoulder(entity, side)` | just above a shoulder (1 right, -1 left) |
| `BodyPoints.local(entity, forward, right, up)` | a point in the entity's own frame, from its feet |
| `BodyPoints.flatLook(entity)` | the look direction with up and down removed |

## Player animations

`PlayerAnims` plays Player Animation Library animations from the server, shown to the player and everyone who can see them. It can also run code at set points of an animation and end it early ([[#Cues and stop conditions]]).

| Call | Does |
|---|---|
| `PlayerAnims.play(entity, id)` | plays animation `id` on a player, blending in over 3 ticks. Any other entity is skipped, so abilities call it for every caster |
| `PlayerAnims.hold(entity, id)` | the same for a looping animation, and players who come into view while it loops are shown it too |
| `PlayerAnims.stop(entity)` | eases the player back to standing (through `dicelib:rest`) |

An id like `mymod:sword.slash` names the animation `sword.slash` in any file under `assets/mymod/player_animations/`. The file name doesn't matter. Files use Blockbench's bedrock animation format with the player's bones: `head`, `body`, `right_arm`, `left_arm`, `right_leg` and `left_leg`. The player faces -Z with the right arm at +X, so an arm at x rotation -90 points forward, and at z 90 (right arm) or -90 (left arm) it points out to the side. Bones the animation doesn't name keep moving normally, so a cast that moves only the arms still walks.

An animation with `"loop": true` plays until replaced or stopped, which is how a charge holds a pose. Start a looping animation with `hold`. `play` only reaches the players watching at that moment, so someone who walks up later would see the caster standing still. The client keeps one animation layer for DiceLib at priority 1500, the library's range for gameplay animations, above emotes. It shows the arms in first person, so casts look right there too.

```java
// a stance held while a shield is raised, and dropped with it
PlayerAnims.hold(player, MyMod.id("shield.raise"));
// ... later
PlayerAnims.stop(player);
```
### Cues and stop conditions

`PlayerAnims.of(entity, id)` sets an animation up with more control, and `play()` starts it. Use it when the ability needs to act at a certain point of the animation, or when something should cut the animation short.

```java
// a 3 second channel that pulses every second and releases at the end
PlayerAnims.of(caster, MyMod.id("staff.channel"))
        .looping()                // the animation file loops, and players who arrive later are shown it too
        .length(60)               // the server counts it as over after 60 ticks
        .at(20, entity -> pulse(entity))          // cues, in server ticks from the start
        .at(40, entity -> pulse(entity))
        .stopWhenHurt()           // ends it early with End.HURT
        .stopWhenMoving()         // ends it early with End.MOVED
        .onEnd((entity, end) -> {
            // DONE means it ran its full length. Any other reason means the channel was lost.
            if (end == PlayerAnims.End.DONE) release(entity);
        })
        .play();
```

| Call | Does |
|---|---|
| `speed(factor)` | plays it faster or slower (2 is double speed) |
| `fadeIn(ticks)` | how long it blends in (3 by default, 0 snaps) |
| `mirrored()` | swaps left and right, so one animation works for both hands |
| `looping()` | the same as `hold`: players who come into view later are shown it |
| `length(ticks)` | how long the server counts it as playing. Without it the animation never reports `DONE` |
| `at(tick, entity -> ...)` | runs on the server that many ticks after the start (0 is immediately), if it is still playing |
| `stopWhenHurt()` | ends it when the entity takes damage |
| `stopWhenMoving()` | ends it once the entity is more than half a block from where it started |
| `stopOnItemSwap()` | ends it when the main hand holds a different item |
| `stopWhen(entity -> ...)` | ends it on the first tick the condition is true |
| `onEnd((entity, end) -> ...)` | runs once however it stops, with the reason |

`End` is `DONE` (the length ran out), `STOPPED` (`stop` was called), `REPLACED` (another animation started), `HURT`, `MOVED`, `SWAPPED`, `CONDITION`, or `GONE` (the entity died or left).

The server can't read animation files, so `length` and the cue ticks are numbers you supply. They are server ticks and don't change with `speed`. `play()` returns a `Playing` handle with `id()`, `tick()`, `isPlaying()` and `stop()`, and `PlayerAnims.playing(entity)` returns the current one. `PlayerAnims.stop(entity, fadeOut)` stops with a blend-out time of your choice.

Only players animate. For a mob the animation is skipped, but the cues, the stop conditions and `onEnd` still run, so the ability behaves the same for every caster.

The library's own documentation is at docs.zigythebird.com/pal/intro.

---

← Previous: [[Timing and State]] · Next: [[Body Parts]] →
